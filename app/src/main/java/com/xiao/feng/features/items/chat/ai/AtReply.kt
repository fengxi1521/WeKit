package com.xiao.feng.features.items.chat.ai

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * 群聊里有人 @ 我 时, 用「AI 聊天」的模型配置按对方说的话回一条。
 *
 * 触发方式与 [AiChat] 相同, 都是宿主消息入库; 生效群聊和「定时消息」一样集中选择,
 * 空集合表示全部群聊。回复优先引用原消息, 引用失败则 @ 对方。
 */
object AtReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "艾特回复"
    override val nameRes = R.string.feature_at_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_at_reply_description

    private const val TAG = "AtReply"

    private const val KEY_TARGETS = "at_reply_targets"
    private const val KEY_DELAY = "at_reply_delay_seconds"

    private const val TEXT_MESSAGE_TYPE = 1
    private const val REPLY_DEBOUNCE_MILLIS = 1_500L
    private const val MAX_DEDUP_KEYS = 256

    /** 生效群聊; 空集合表示全部群聊。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    var replyDelaySeconds: Int by prefOption(KEY_DELAY, 2)

    private var scope = newScope()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)

    override fun onClick(context: ComponentActivity) {
        showAtReplyDialog(context)
    }

    /** 该会话是否由「艾特回复」接管; AI 聊天据此避让, 避免一条消息回两遍。 */
    fun isActiveFor(talker: String): Boolean =
        isEnabled && AiChat.isConfigured() && (targets.isEmpty() || talker in targets)

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        scope = newScope()
        synchronized(dedupKeys) { dedupKeys.clear() }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return
        if ((values.getAsInteger("type") ?: return) != TEXT_MESSAGE_TYPE) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.isGroupChatWxId) return
        if (targets.isNotEmpty() && talker !in targets) return

        val rawContent = values.getAsString("content") ?: return
        val sender = AiChat.groupSenderOf(rawContent)
        if (sender.isBlank() || sender == WeApi.selfWxId) return

        val text = rawContent.stripWxId().trim()
        if (text.isEmpty()) return
        if (!AiChat.isMentioningMe(talker, text)) return
        if (!AiChat.isConfigured()) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val createTime = values.getAsLong("createTime") ?: 0L
        val key = "$talker|$msgId|$createTime|$text"
        if (!markDedup(key)) return

        scope.launch {
            delay(REPLY_DEBOUNCE_MILLIS + replyDelaySeconds.coerceAtLeast(0) * 1000L)
            val answer = runCatching {
                AiChat.requestReplyFor(talker, sender, msgId, text)
            }.onFailure {
                WeLogger.w(TAG, "failed to generate reply for $talker", it)
            }.getOrNull() ?: return@launch

            val sent = runCatching {
                AiChat.sendQuotedOrMentioned(talker, sender, msgId, createTime, answer)
            }.getOrDefault(false)
            WeLogger.i(TAG, "at reply talker=$talker sender=$sender sent=$sent")
        }
    }

    private fun markDedup(key: String): Boolean = synchronized(dedupKeys) {
        if (dedupKeys.containsKey(key)) return@synchronized false
        if (dedupKeys.size >= MAX_DEDUP_KEYS) dedupKeys.entries.iterator().run {
            next()
            remove()
        }
        dedupKeys[key] = Unit
        true
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
