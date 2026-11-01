package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
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
    val reply: String = "",
    /** `true` = the whole message must equal a keyword, `false` = substring match. */
    val matchExact: Boolean = false,
    val enabled: Boolean = true,
)

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

    private const val TAG = "KeywordAutoReply"
    private const val KEY_ENABLED = "keyword_auto_reply_enabled"
    private const val KEY_RULES = "keyword_auto_reply_rules"

    /** WeChat's plain-text row type (`MessageType.TEXT.code`). */
    private const val TEXT_MESSAGE_TYPE = 1

    private const val SEND_DELAY_MILLIS = 500L
    private const val REPLY_COOLDOWN_MILLIS = 5000L
    private const val MAX_DEDUP_KEYS = 128

    var featureEnabled: Boolean by prefOption(KEY_ENABLED, true)

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

        val rule = decodeRules().firstOrNull { it.matches(content) } ?: return
        val dedupKey = "$talker|$content|${values.get("createTime")}|${values.get("msgId")}"
        val now = System.currentTimeMillis()
        if (!markReplyAllowed(dedupKey, talker, now)) return

        scope.launch {
            delay(SEND_DELAY_MILLIS)
            val sent = runCatching { WeMessageApi.sendText(talker, rule.reply.trim()) }
                .getOrDefault(false)
            WeLogger.i(TAG, "auto reply rule=${rule.id} talker=$talker sent=$sent")
        }
    }

    private fun AutoReplyRule.matches(text: String): Boolean {
        if (!enabled || reply.isBlank()) return false
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
