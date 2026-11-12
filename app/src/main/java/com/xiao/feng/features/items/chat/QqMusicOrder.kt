package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import java.io.ByteArrayOutputStream
import java.io.File
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
    private const val MAX_DEDUP_KEYS = 256
    private const val APP_ID = "wx485a97c844086dc9"
    private const val DEFAULT_TRIGGERS = "点歌"

    private const val KEY_TARGETS = "qq_music_order_targets"
    private const val KEY_ALLOW_SELF = "qq_music_order_allow_self"
    private const val KEY_TRIGGERS = "qq_music_order_triggers"
    private const val KEY_CUSTOM_SINGER = "qq_music_order_custom_singer"
    private const val KEY_DEFAULT_SINGER = "qq_music_order_default_singer"
    private const val KEY_REPLACE_SINGER = "qq_music_order_replace_singer"
    private const val KEY_REPLACE_COVER = "qq_music_order_replace_cover"

    /** 允许其他人点歌的会话; 为空时只有自己发出的指令生效。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    var allowSelfCommand: Boolean by prefOption(KEY_ALLOW_SELF, true)

    var triggersRaw: String by prefOption(KEY_TRIGGERS, DEFAULT_TRIGGERS)

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
        val coverUrl = if (replaceCoverWithAvatar) {
            WeDatabaseApi.getAvatarUrl(sender).ifBlank { track.coverUrl }
        } else {
            track.coverUrl
        }
        val thumbData = loadThumb(coverUrl.replace("R500x500", "R300x300"))
        val sent = WeMessageApi.shareMusic(
            talker,
            track.title,
            singer,
            track.landingUrl,
            track.playUrl,
            thumbData,
            APP_ID,
        )
        if (!sent) {
            sendReply(talker, HostLocalizedStrings.get(R.string.qq_music_send_failed))
        }
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
