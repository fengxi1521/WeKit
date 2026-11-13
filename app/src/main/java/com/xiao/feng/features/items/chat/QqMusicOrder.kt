package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.utils.AudioUtils
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import dev.ujhhgtg.reflekt.reflekt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 在聊天里发送 "点歌 歌名", 搜索 QQ 音乐并回一张可播放的音乐卡片。
 *
 * 移植自 Hchat: 触发点是宿主自己的消息入库, 音乐卡片走 `WXMusicObject` 分享通道,
 * 不需要额外的 Dex 目标。
 */
object QqMusicOrder : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "QQ 点歌"
    override val nameRes = R.string.feature_qq_music_order_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_qq_music_order_description

    private const val TAG = "QqMusicOrder"
    private const val TEXT_MESSAGE_TYPE = 1
    private const val MESSAGE_MAX_AGE_MS = 30_000L
    private const val MAX_THUMB_BYTES = 128 * 1024
    private const val MAX_AUDIO_BYTES = 128L * 1024L * 1024L
    private const val AUDIO_CONNECT_TIMEOUT_MS = 15_000
    private const val AUDIO_READ_TIMEOUT_MS = 30_000
    private const val MAX_DEDUP_KEYS = 256
    private const val APP_ID = "wx485a97c844086dc9"
    private const val DEFAULT_TRIGGERS = "点歌"
    private const val ENABLE_CHAT_COMMAND = "开启点歌"
    private const val DISABLE_CHAT_COMMAND = "关闭点歌"
    private const val VOICE_CACHE_DIR = "xfeng_qq_music_order_voice"
    private val SUPPORTED_AUDIO_SUFFIXES = listOf(".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".wav")

    private const val KEY_TARGETS = "qq_music_order_targets"
    private const val KEY_ALLOW_SELF = "qq_music_order_allow_self"
    private const val KEY_TRIGGERS = "qq_music_order_triggers"
    private const val KEY_SEND_CARD = "qq_music_order_send_card"
    private const val KEY_SEND_VOICE = "qq_music_order_send_voice"
    private const val KEY_CUSTOM_SINGER = "qq_music_order_custom_singer"
    private const val KEY_DEFAULT_SINGER = "qq_music_order_default_singer"
    private const val KEY_REPLACE_SINGER = "qq_music_order_replace_singer"
    private const val KEY_REPLACE_COVER = "qq_music_order_replace_cover"

    /** 允许其他人点歌的会话; 为空时只有自己发出的指令生效。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    var allowSelfCommand: Boolean by prefOption(KEY_ALLOW_SELF, true)

    var triggersRaw: String by prefOption(KEY_TRIGGERS, DEFAULT_TRIGGERS)

    /** 发送可播放的音乐卡片。 */
    var sendAsCard: Boolean by prefOption(KEY_SEND_CARD, true)

    /** 同时把歌曲下载并转成语音发送 (silk)。 */
    var sendAsVoice: Boolean by prefOption(KEY_SEND_VOICE, false)

    var customSingerEnabled: Boolean by prefOption(KEY_CUSTOM_SINGER, false)

    var defaultSinger: String by prefOption(KEY_DEFAULT_SINGER, "")

    var replaceSingerWithNickname: Boolean by prefOption(KEY_REPLACE_SINGER, false)

    var replaceCoverWithAvatar: Boolean by prefOption(KEY_REPLACE_COVER, false)

    val triggers: List<String>
        get() = triggersRaw.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(DEFAULT_TRIGGERS) }

    private val client = QqMusicClient()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QqMusicOrder").apply { isDaemon = true }
    }
    private val processed = ConcurrentHashMap<String, Boolean>()

    override fun onEnable() {
        WeDatabaseListenerApi.removeListener(this)
        WeDatabaseListenerApi.addListener(this)

        // 聊天内开关指令: 在发送前拦下 "开启点歌"/"关闭点歌", 只更新本会话的允许名单,
        // 不把指令本身发进聊天 (清空输入框, 原生发送键会因此直接返回)。
        WeChatInputBarMenuApi.methodSendMessage.hookBefore {
            if (!isEnabled) return@hookBefore
            val chatFooter = thisObject!!.reflekt()
                .firstField { type = ChatFooter::class }
                .get()!! as ChatFooter
            val content = chatFooter.lastText.trim()
            val allowed = when (content) {
                ENABLE_CHAT_COMMAND -> true
                DISABLE_CHAT_COMMAND -> false
                else -> return@hookBefore
            }
            val talker = WeCurrentConversationApi.value
            if (talker.isBlank()) return@hookBefore
            chatFooter.lastText = ""
            setTalkerAllowed(talker, allowed)
        }
    }

    private fun setTalkerAllowed(talker: String, allowed: Boolean) {
        val next = targets.toMutableSet()
        if (allowed) next.add(talker) else next.remove(talker)
        targets = next
        val message = HostLocalizedStrings.get(
            if (allowed) R.string.qq_music_toggle_enabled else R.string.qq_music_toggle_disabled,
        )
        WeMessageApi.createSimpleMsgInfoAndInsert(
            MessageType.SYSTEM.code,
            talker,
            message,
            System.currentTimeMillis(),
        )
        WeLogger.i(TAG, "music order ${if (allowed) "enabled" else "disabled"} for $talker")
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        processed.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message" || !isEnabled) return
        if ((values.getAsInteger("type") ?: return) != TEXT_MESSAGE_TYPE) return

        val talker = values.getAsString("talker") ?: return
        if (talker.isBlank() || talker.startsWith("gh_")) return

        val isSend = (values.getAsInteger("isSend") ?: 0) != 0
        if (!isSend) {
            if (!targets.contains(talker)) return
        } else if (!allowSelfCommand) {
            return
        }

        if (!isFresh(values)) return

        val rawContent = values.getAsString("content") ?: return
        val content = (if (talker.isGroupChatWxId) rawContent.stripWxId() else rawContent).trim()
        val command = parseCommand(content) ?: return

        val msgId = values.getAsLong("msgId") ?: 0L
        val dedupKey = if (msgId > 0L) "$talker:$msgId" else "$talker:$content:${values.getAsLong("createTime")}"
        if (processed.putIfAbsent(dedupKey, true) != null) return
        if (processed.size > MAX_DEDUP_KEYS) processed.keys.firstOrNull()?.let(processed::remove)

        val sender = when {
            isSend -> WeApi.selfWxId
            talker.isGroupChatWxId -> rawContent.substringBefore(':')
            else -> talker
        }
        WeLogger.i(TAG, "music order: talker=$talker, keyword=${command.keyword}, sender=$sender")
        executor.execute {
            runCatching { process(talker, sender, command) }
                .onFailure { WeLogger.e(TAG, "failed to process music order", it) }
        }
    }

    private fun isFresh(values: ContentValues): Boolean {
        val raw = values.getAsLong("createTime") ?: return true
        if (raw <= 0L) return true
        val createdMs = if (raw < 100_000_000_000L) raw * 1000L else raw
        return System.currentTimeMillis() - createdMs <= MESSAGE_MAX_AGE_MS
    }

    private fun process(talker: String, sender: String, command: MusicCommand) {
        val track = when (val result = client.search(command.keyword)) {
            is QqMusicSearchResult.Success -> result.track
            QqMusicSearchResult.NotFound -> {
                sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_not_found))
                return
            }
            QqMusicSearchResult.Unavailable -> {
                sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_unavailable))
                return
            }
        }

        val singer = resolveSinger(talker, sender, command.customSinger, track.singer)
        if (!sendAsCard && !sendAsVoice) {
            sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_channel_required))
            return
        }
        val sentCard = if (sendAsCard) {
            val coverUrl = if (replaceCoverWithAvatar) {
                WeDatabaseApi.getAvatarUrl(sender).ifBlank { track.coverUrl }
            } else {
                track.coverUrl
            }
            val thumbData = loadThumb(coverUrl.replace("R500x500", "R300x300"))
            WeMessageApi.shareMusic(
                talker,
                track.title,
                singer,
                track.landingUrl,
                track.playUrl,
                thumbData,
                APP_ID,
            )
        } else {
            null
        }
        val sentVoice = if (sendAsVoice) sendTrackAsVoice(talker, track) else null
        when {
            sentCard != false && sentVoice != false -> Unit
            sentCard == false && sentVoice == false ->
                sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_both_failed))
            sentCard == false ->
                sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_send_failed))
            else ->
                sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_voice_failed))
        }
    }

    /**
     * 下载歌曲并转成 silk 语音发送。QQ 音乐的播放地址是 m4a/mp3 流, 需要先落盘,
     * 再用 [AudioUtils] 转成微信语音格式。
     */
    private fun sendTrackAsVoice(talker: String, track: QqMusicTrack): Boolean {
        if (track.playUrl.isBlank()) return false
        val cacheDir = File(HostInfo.application.cacheDir, VOICE_CACHE_DIR)
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            WeLogger.w(TAG, "failed to create voice cache dir: $cacheDir")
            return false
        }
        val stamp = "${System.currentTimeMillis()}_${System.nanoTime()}"
        val audio = File(cacheDir, "qq_music_$stamp${audioSuffix(track.playUrl)}")
        val part = File(audio.absolutePath + ".part")
        val silk = File(cacheDir, "qq_music_$stamp.silk")
        return try {
            if (!downloadAudio(track.playUrl, part, audio)) return false
            val durationMs = AudioUtils.getDurationMs(audio.absolutePath)
            if (durationMs <= 0L) {
                WeLogger.w(TAG, "invalid audio duration for ${track.title}")
                return false
            }
            if (!AudioUtils.anyToSilk(audio.absolutePath, silk.absolutePath)) {
                WeLogger.w(TAG, "failed to convert ${track.title} to silk")
                return false
            }
            WeMessageApi.sendVoice(
                talker,
                silk.absolutePath,
                durationMs.coerceIn(1L, 60_000L).toInt(),
            )
        } finally {
            part.delete()
            audio.delete()
            silk.delete()
        }
    }

    private fun audioSuffix(url: String): String {
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return SUPPORTED_AUDIO_SUFFIXES.firstOrNull(path::endsWith) ?: ".audio"
    }

    private fun downloadAudio(url: String, part: File, target: File): Boolean {
        var connection: HttpURLConnection? = null
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = AUDIO_CONNECT_TIMEOUT_MS
            conn.readTimeout = AUDIO_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MicroMessenger Client")
            conn.setRequestProperty("Referer", "https://y.qq.com/")
            if (conn.responseCode !in 200..299) return@runCatching false
            val contentType = conn.contentType.orEmpty().substringBefore(';').lowercase()
            if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                return@runCatching false
            }
            if (conn.contentLengthLong > MAX_AUDIO_BYTES) return@runCatching false
            var total = 0L
            var oversized = false
            conn.inputStream.use { input ->
                FileOutputStream(part, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_AUDIO_BYTES) {
                            oversized = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (oversized || !part.isFile || part.length() <= 0L) return@runCatching false
            if (target.exists() && !target.delete()) return@runCatching false
            part.renameTo(target) && target.isFile && target.length() > 0L
        }.onFailure { WeLogger.w(TAG, "failed to download audio: $url", it) }
            .getOrDefault(false)
            .also { connection?.disconnect() }
    }

    private fun resolveSinger(talker: String, sender: String, customSinger: String?, sourceSinger: String): String {
        if (!customSinger.isNullOrBlank()) return customSinger
        if (customSingerEnabled && defaultSinger.isNotBlank()) return defaultSinger
        if (replaceSingerWithNickname && sender.isNotBlank()) {
            val nickname = runCatching {
                if (talker.isGroupChatWxId) {
                    WeDatabaseApi.getGroupMemberDisplayNames(talker)[sender]
                        ?: WeDatabaseApi.getDisplayName(sender)
                } else {
                    WeDatabaseApi.getDisplayName(sender)
                }
            }.getOrDefault("")
            if (nickname.isNotBlank() && nickname != sender) return nickname
        }
        return sourceSinger
    }

    private fun parseCommand(text: String): MusicCommand? {
        val clean = text.trim()
        val trigger = triggers.firstOrNull { clean.startsWith(it) } ?: return null
        var keyword = clean.removePrefix(trigger).trim()
        var customSinger: String? = null
        if (customSingerEnabled) {
            val separator = keyword.indexOf('&')
            if (separator >= 0) {
                customSinger = keyword.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
                keyword = keyword.substring(0, separator).trim()
            }
        }
        return keyword.takeIf { it.isNotEmpty() }?.let { MusicCommand(it, customSinger) }
    }

    private fun sendReply(talker: String, content: String) {
        WeMessageApi.sendText(talker, content)
    }

    private fun loadThumb(source: String): ByteArray? {
        if (source.isBlank()) return null
        if (!source.startsWith("http://") && !source.startsWith("https://")) {
            return runCatching {
                val file = File(source)
                if (!file.isFile || file.length() > MAX_THUMB_BYTES) return@runCatching null
                file.readBytes()
            }.getOrNull()
        }
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = URL(source).openConnection() as HttpURLConnection
            connection?.connectTimeout = 10_000
            connection?.readTimeout = 10_000
            connection?.instanceFollowRedirects = true
            connection?.setRequestProperty("User-Agent", "MicroMessenger Client")
            val responseCode = connection?.responseCode ?: return@runCatching null
            if (responseCode !in 200..299) return@runCatching null
            connection?.inputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_THUMB_BYTES) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.onFailure { WeLogger.w(TAG, "failed to load cover: $source", it) }
            .getOrNull()
            .also { connection?.disconnect() }
    }

    override fun onClick(context: ComponentActivity) = showQqMusicOrderDialog(context)

    private data class MusicCommand(val keyword: String, val customSinger: String?)
}
