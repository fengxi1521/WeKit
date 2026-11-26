package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.chat.scheduledmessages.ScheduledMessageContent
import com.xiao.feng.features.items.chat.scheduledmessages.ScheduledMessageContentType
import com.xiao.feng.features.items.chat.scheduledmessages.sendScheduledContentItem
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.serialization.DefaultJson
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AutoReplyRule(
    val id: String,
    val keywords: List<String> = emptyList(),
    /** 旧版纯文字回复; [items] 为空时回退到这里。 */
    val reply: String = "",
    /** `true` = the whole message must equal a keyword, `false` = substring match. */
    val matchExact: Boolean = false,
    val enabled: Boolean = true,
    /** 回复内容, 复用定时消息的内容模型 (文字/图片/语音/视频/表情/文件/XML)。 */
    val items: List<ScheduledMessageContent> = emptyList(),
    /** 本条规则单独生效的会话; 为空时跟随全局 [KeywordAutoReply.targets]。 */
    val targets: Set<String> = emptySet(),
)

/** [items] 与旧版 [AutoReplyRule.reply] 归一后的内容列表, 空值已剔除。 */
fun AutoReplyRule.effectiveItems(): List<ScheduledMessageContent> {
    val source = if (items.isEmpty() && reply.isNotBlank()) {
        listOf(ScheduledMessageContent(ScheduledMessageContentType.TEXT, reply))
    } else {
        items
    }
    return source
        .map { ScheduledMessageContent(it.type, it.value.trim()) }
        .filter { it.value.isNotBlank() }
}

fun newAutoReplyRuleId(): String = "autoreply_${System.currentTimeMillis()}"

/**
 * Replies to incoming plain-text messages when their content matches a configured keyword.
 *
 * The trigger is the host's own message-row insert (`WeDatabaseListenerApi.IInsertListener`), so no
 * Dex target is involved. Outgoing rows (`isSend == 1`) never match, which also prevents the
 * auto-reply from answering itself.
 */
object KeywordAutoReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "关键词自动回复"
    override val nameRes = R.string.feature_keyword_auto_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_keyword_auto_reply_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    /**
     * 宿主在后台时, 新消息由 :push 进程接收并写入消息库; 只在主进程挂钩会完全漏掉后台消息。
     * 是否真的回复由 [featureEnabled] 在每次消息时判断。
     */
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val TAG = "KeywordAutoReply"
    private const val KEY_ENABLED = "keyword_auto_reply_enabled"
    private const val KEY_RULES = "keyword_auto_reply_rules"
    private const val KEY_TARGETS = "keyword_auto_reply_targets"

    /** WeChat's plain-text row type (`MessageType.TEXT.code`). */
    private const val TEXT_MESSAGE_TYPE = 1

    private const val SEND_DELAY_MILLIS = 500L
    private const val ITEM_INTERVAL_MILLIS = 300L
    private const val REPLY_COOLDOWN_MILLIS = 5000L
    private const val MAX_DEDUP_KEYS = 128

    var featureEnabled: Boolean by prefOption(KEY_ENABLED, true)

    /** 生效会话; 为空表示所有聊天。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    private var rulesRaw: String by prefOption(KEY_RULES, "")

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedRules: List<AutoReplyRule> = emptyList()

    var rules: List<AutoReplyRule>
        get() = decodeRules()
        set(value) {
            rulesRaw = runCatching { DefaultJson.encodeToString(value) }
                .onFailure { WeLogger.w(TAG, "failed to save auto-reply rules", it) }
                .getOrDefault("")
        }

    private var scope = newScope()
    private val dedupKeys = ConcurrentHashMap<String, Long>()
    private val lastReplyAt = ConcurrentHashMap<String, Long>()

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        dedupKeys.clear()
        lastReplyAt.clear()
    }

    private fun decodeRules(): List<AutoReplyRule> {
        val raw = rulesRaw
        if (raw.isEmpty()) return emptyList()
        if (raw == cachedRaw) return cachedRules
        val parsed = runCatching { DefaultJson.decodeFromString<List<AutoReplyRule>>(raw) }
            .onFailure { WeLogger.w(TAG, "failed to parse auto-reply rules", it) }
            .getOrDefault(emptyList())
        cachedRaw = raw
        cachedRules = parsed
        return parsed
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message" || !featureEnabled) return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return
        val type = values.getAsInteger("type") ?: return
        if (type != TEXT_MESSAGE_TYPE) return

        val talker = values.getAsString("talker") ?: return
        val rawContent = values.getAsString("content") ?: return
        val content = (if (talker.isGroupChatWxId) rawContent.stripWxId() else rawContent).trim()
        if (content.isEmpty()) return

        val rule = decodeRules().firstOrNull { it.matches(content) && it.inScope(talker) } ?: return
        val dedupKey = "$talker|$content|${values.get("createTime")}|${values.get("msgId")}"
        val now = System.currentTimeMillis()
        if (!markReplyAllowed(dedupKey, talker, now)) return

        scope.launch {
            delay(SEND_DELAY_MILLIS)
            val items = rule.effectiveItems()
            // 支持 %friendName% 占位符, 与定时消息保持一致。
            val displayName = runCatching { WeDatabaseApi.getDisplayName(talker) }
                .getOrDefault(talker)
            var sent = 0
            items.forEachIndexed { index, item ->
                if (index > 0) delay(ITEM_INTERVAL_MILLIS)
                if (runCatching { sendScheduledContentItem(item, talker, displayName) }
                        .getOrDefault(false)
                ) {
                    sent++
                }
            }
            WeLogger.i(
                TAG,
                "auto reply rule=${rule.id} talker=$talker items=${items.size} sent=$sent",
            )
        }
    }

    /** 规则自己的会话范围优先; 未单独指定时跟随全局 [targets]。 */
    private fun AutoReplyRule.inScope(talker: String): Boolean {
        val effectiveTargets = if (targets.isNotEmpty()) targets else this@KeywordAutoReply.targets
        return effectiveTargets.isEmpty() || talker in effectiveTargets
    }

    private fun AutoReplyRule.matches(text: String): Boolean {
        if (!enabled || effectiveItems().isEmpty()) return false
        val normalized = text.lowercase()
        return keywords.any { keyword ->
            val k = keyword.trim().lowercase()
            when {
                k.isEmpty() -> false
                matchExact -> normalized == k
                else -> normalized.contains(k)
            }
        }
    }

    /** Deduplicates host re-inserts and throttles repeated replies to one conversation. */
    private fun markReplyAllowed(dedupKey: String, talker: String, now: Long): Boolean =
        synchronized(dedupKeys) {
            val duplicate = dedupKeys.containsKey(dedupKey)
            val coolingDown = lastReplyAt[talker]?.let { now - it < REPLY_COOLDOWN_MILLIS } ?: false
            if (duplicate || coolingDown) {
                false
            } else {
                if (dedupKeys.size >= MAX_DEDUP_KEYS) dedupKeys.entries.iterator().run {
                    next()
                    remove()
                }
                dedupKeys[dedupKey] = now
                lastReplyAt[talker] = now
                true
            }
        }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onClick(context: ComponentActivity) = showKeywordAutoReplyDialog(context)
}
