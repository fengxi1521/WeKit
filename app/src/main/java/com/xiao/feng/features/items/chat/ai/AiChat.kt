package com.xiao.feng.features.items.chat.ai

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.agent.data.entity.ModelEntity
import com.xiao.feng.agent.data.entity.ModelProviderEntity
import com.xiao.feng.agent.data.entity.ModelProviderType
import com.xiao.feng.agent.model.LlmMessage
import com.xiao.feng.agent.model.LlmRole
import com.xiao.feng.agent.model.LlmStreamEvent
import com.xiao.feng.agent.model.ModelProviderManager
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.WeMessage
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 在用户挑选的群聊与私聊里接入 DeepSeek 的聊天机器人。
 *
 * 触发点是宿主自己的消息入库 (`WeDatabaseListenerApi.IInsertListener`), 因此不需要额外的
 * Dex 目标。收到的文本消息会连同该会话最近的若干条消息一起发给 DeepSeek, 回复沿用普通
 * 文本消息发送; 群聊里可以按概率 @ 发言人。另有一个后台循环, 在用户设置的时间段内不定时
 * 主动"冒泡"发一条消息。
 */
object AiChat : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "AI 聊天"
    override val nameRes = R.string.feature_ai_chat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_chat_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    private const val TAG = "AiChat"

    private const val PROVIDER_ID = "chat_ai"
    private const val MODEL_ROW_ID = "chat_ai_model"
    private const val SESSION_ID = "chat-ai"
    private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    private const val DEFAULT_MODEL = "deepseek-flash"
    private const val REQUEST_TIMEOUT_MS = 60_000L

    /** 微信纯文本行类型 (`MessageType.TEXT.code`)。 */
    private const val TEXT_MESSAGE_TYPE = 1

    /** 同一会话里连续发言时, 等最后一条消息静下来再回复, 避免逐条抢答。 */
    private const val REPLY_DEBOUNCE_MILLIS = 3_500L

    /** 同一会话两次自动回复的最小间隔。 */
    private const val REPLY_COOLDOWN_MILLIS = 15_000L

    /** 会话最近有消息时不冒泡, 避免打断正在进行的聊天。 */
    private const val BUBBLE_IDLE_MILLIS = 10L * 60L * 1000L

    private const val MAX_REPLY_CHARS = 200
    private const val MAX_DEDUP_KEYS = 256

    private const val KEY_ENABLED = "ai_chat_enabled"
    private const val KEY_TARGETS = "ai_chat_targets"
    private const val KEY_API_KEY = "ai_chat_api_key"
    private const val KEY_BASE_URL = "ai_chat_base_url"
    private const val KEY_MODEL = "ai_chat_model"
    private const val KEY_PERSONA = "ai_chat_persona"
    private const val KEY_CONTEXT_COUNT = "ai_chat_context_count"
    private const val KEY_MENTION_ENABLED = "ai_chat_mention_enabled"
    private const val KEY_MENTION_PERCENT = "ai_chat_mention_percent"
    private const val KEY_REPLY_DELAY = "ai_chat_reply_delay_seconds"
    private const val KEY_BUBBLE_ENABLED = "ai_chat_bubble_enabled"
    private const val KEY_BUBBLE_START = "ai_chat_bubble_start_minute"
    private const val KEY_BUBBLE_END = "ai_chat_bubble_end_minute"
    private const val KEY_BUBBLE_MIN = "ai_chat_bubble_min_minutes"
    private const val KEY_BUBBLE_MAX = "ai_chat_bubble_max_minutes"

    var enabled: Boolean by prefOption(KEY_ENABLED, false)
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())
    var apiKey: String by prefOption(KEY_API_KEY, "")
    var baseUrl: String by prefOption(KEY_BASE_URL, DEFAULT_BASE_URL)
    var model: String by prefOption(KEY_MODEL, DEFAULT_MODEL)
    var persona: String by prefOption(KEY_PERSONA, DEFAULT_PERSONA)
    var contextCount: Int by prefOption(KEY_CONTEXT_COUNT, 12)
    var mentionEnabled: Boolean by prefOption(KEY_MENTION_ENABLED, true)
    var mentionPercent: Int by prefOption(KEY_MENTION_PERCENT, 30)
    var replyDelaySeconds: Int by prefOption(KEY_REPLY_DELAY, 4)
    var bubbleEnabled: Boolean by prefOption(KEY_BUBBLE_ENABLED, false)
    var bubbleStartMinute: Int by prefOption(KEY_BUBBLE_START, 9 * 60)
    var bubbleEndMinute: Int by prefOption(KEY_BUBBLE_END, 23 * 60)
    var bubbleMinMinutes: Int by prefOption(KEY_BUBBLE_MIN, 30)
    var bubbleMaxMinutes: Int by prefOption(KEY_BUBBLE_MAX, 120)

    private var scope = newScope()
    private var bubbleJob: Job? = null

    /** 每个会话的发言代次: 新消息到达会作废旧消息排队的回复。 */
    private val generations = ConcurrentHashMap<String, Int>()
    private val lastReplyAt = ConcurrentHashMap<String, Long>()
    private val dedupKeys = ConcurrentHashMap<String, Long>()

    override fun onEnable() {
        if (!scope.isActive) scope = newScope()
        WeDatabaseListenerApi.addListener(this)
        restartAutomation()
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        bubbleJob?.cancel()
        bubbleJob = null
        dedupKeys.clear()
        lastReplyAt.clear()
        generations.clear()
        scope.cancel()
        scope = newScope()
    }

    /** 设置变化后重新评估冒泡循环。 */
    fun restartAutomation() {
        bubbleJob?.cancel()
        bubbleJob = null
        if (!enabled || !bubbleEnabled || targets.isEmpty()) return
        bubbleJob = scope.launch {
            while (isActive) {
                delay(nextBubbleIntervalMillis())
                if (!enabled || !bubbleEnabled) continue
                if (!withinBubbleWindow()) continue
                val talker = targets.randomOrNull() ?: continue
                runCatching { bubble(talker) }
                    .onFailure { WeLogger.w(TAG, "bubble failed for $talker", it) }
            }
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message" || !enabled) return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return
        if ((values.getAsInteger("type") ?: return) != TEXT_MESSAGE_TYPE) return

        val talker = values.getAsString("talker") ?: return
        if (talker !in targets) return

        val rawContent = values.getAsString("content") ?: return
        val sender = if (talker.isGroupChatWxId) groupSenderOf(rawContent) else talker
        if (sender == WeApi.selfWxId) return

        val text = (if (talker.isGroupChatWxId) rawContent.stripWxId() else rawContent).trim()
        if (text.isEmpty()) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val dedupKey = "$talker|$msgId|${values.getAsLong("createTime") ?: 0L}|$text"
        if (!markSeen(dedupKey)) return

        val generation = nextGeneration(talker)
        scope.launch {
            delay(REPLY_DEBOUNCE_MILLIS + replyDelaySeconds.coerceAtLeast(0) * 1000L)
            if (generations[talker] != generation) return@launch
            if (!enabled || talker !in targets) return@launch
            val now = System.currentTimeMillis()
            if (now - (lastReplyAt[talker] ?: 0L) < REPLY_COOLDOWN_MILLIS) return@launch
            lastReplyAt[talker] = now
            runCatching { reply(talker, sender, msgId, text) }
                .onFailure { WeLogger.w(TAG, "reply failed for $talker", it) }
        }
    }

    private suspend fun reply(talker: String, sender: String, triggerMsgId: Long, triggerText: String) {
        val config = snapshot()
        if (config.apiKey.isBlank()) {
            WeLogger.w(TAG, "skipped: DeepSeek API key is not configured")
            return
        }

        val messages = buildMessages(
            talker = talker,
            config = config,
            triggerMsgId = triggerMsgId,
            triggerSender = sender,
            triggerText = triggerText,
            bubble = false,
        )
        val answer = ask(config, messages) ?: return

        val atSender = talker.isGroupChatWxId &&
                config.mentionEnabled &&
                sender.isNotBlank() &&
                sender != WeApi.selfWxId &&
                Random.nextInt(100) < config.mentionPercent.coerceIn(0, 100)

        val sent = if (atSender) {
            WeMessageApi.sendTextWithMsgSource(talker, answer, atMsgSource(sender))
        } else {
            WeMessageApi.sendText(talker, answer)
        }
        WeLogger.i(TAG, "reply talker=$talker mention=$atSender sent=$sent")
    }

    private suspend fun bubble(talker: String) {
        val config = snapshot()
        if (config.apiKey.isBlank()) return
        val last = runCatching { WeDatabaseApi.getMessages(talker, 1, 1).firstOrNull() }.getOrNull()
        val lastMillis = last?.createTime?.times(1000L) ?: 0L
        if (System.currentTimeMillis() - lastMillis < BUBBLE_IDLE_MILLIS) return

        val messages = buildMessages(
            talker = talker,
            config = config,
            triggerMsgId = null,
            triggerSender = "",
            triggerText = "",
            bubble = true,
        )
        val answer = ask(config, messages) ?: return
        val sent = WeMessageApi.sendText(talker, answer)
        WeLogger.i(TAG, "bubble talker=$talker sent=$sent")
    }

    private fun buildMessages(
        talker: String,
        config: Config,
        triggerMsgId: Long?,
        triggerSender: String,
        triggerText: String,
        bubble: Boolean,
    ): List<LlmMessage> {
        val isGroup = talker.isGroupChatWxId
        val conversationName = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        val memberNames = if (isGroup) {
            runCatching { WeDatabaseApi.getGroupMemberDisplayNames(talker) }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val history = if (config.contextCount > 0 && WeDatabaseApi.isReady) {
            runCatching {
                WeDatabaseApi.getMessages(talker, 1, config.contextCount).reversed()
            }.onFailure {
                WeLogger.w(TAG, "failed to read chat history for $talker", it)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        fun speakerOf(message: WeMessage): String = when {
            message.isSend == 1 -> "我"
            isGroup -> {
                val sender = groupSenderOf(message.content)
                memberNames[sender]?.takeIf { it.isNotBlank() }
                    ?: runCatching { WeDatabaseApi.getDisplayName(sender) }.getOrDefault(sender)
            }

            else -> conversationName
        }

        val conversation = history.mapNotNull { message ->
            if (message.typeCode != TEXT_MESSAGE_TYPE) return@mapNotNull null
            val text = (if (isGroup) message.content.stripWxId() else message.content).trim()
            if (text.isEmpty()) return@mapNotNull null
            val role = if (message.isSend == 1) LlmRole.ASSISTANT else LlmRole.USER
            LlmMessage(role, if (isGroup) "${speakerOf(message)}：$text" else text)
        }.toMutableList()

        // 入库回调里的这条消息有时还没落到查询可见的位置, 补一条保证模型看到最新发言。
        if (!bubble && triggerText.isNotBlank() && history.none { it.msgId == triggerMsgId }) {
            val senderName = if (isGroup) {
                memberNames[triggerSender]?.takeIf { it.isNotBlank() }
                    ?: runCatching { WeDatabaseApi.getDisplayName(triggerSender) }
                        .getOrDefault(triggerSender)
            } else {
                conversationName
            }
            conversation += LlmMessage(
                LlmRole.USER,
                if (isGroup) "$senderName：$triggerText" else triggerText,
            )
        }

        val system = LlmMessage(
            LlmRole.SYSTEM,
            systemPrompt(
                config = config,
                conversationName = conversationName,
                isGroup = isGroup,
                bubble = bubble,
            ),
        )
        return listOf(system) + conversation
    }

    private fun systemPrompt(
        config: Config,
        conversationName: String,
        isGroup: Boolean,
        bubble: Boolean,
    ): String {
        val now = Calendar.getInstance()
        val time = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        return buildString {
            append(config.persona.trim().ifBlank { DEFAULT_PERSONA })
            append("\n\n")
            append(
                if (isGroup) "你正在微信群聊「$conversationName」里，以一位真实群友的身份参与聊天。"
                else "你正在微信私聊里和好友「$conversationName」聊天，以对方好友的身份回复。"
            )
            append("\n回复要求：")
            append("\n1. 只输出要发送的聊天内容，不要解释、不要加引号、不要带“昵称：”前缀。")
            append("\n2. 承接上文，口语化、简短自然，一般不超过 50 个字。")
            append("\n3. 不要重复别人刚说过的话，也不要暴露自己是 AI 或机器人。")
            append("\n4. 不要使用 Markdown、列表或标题，可以用表情。")
            if (bubble) {
                append("\n\n现在聊天比较冷清，请你主动冒个泡：发一句能开启话题或活跃气氛的话。")
            }
            append("\n当前时间：$time。")
        }
    }

    private suspend fun ask(config: Config, messages: List<LlmMessage>): String? {
        val provider = ModelProviderEntity(
            id = PROVIDER_ID,
            type = ModelProviderType.OPENAI_CHAT_COMPLETION,
            name = "DeepSeek",
            baseUrl = config.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
            apiKey = config.apiKey.trim(),
        )
        val model = ModelEntity(
            id = MODEL_ROW_ID,
            providerId = PROVIDER_ID,
            modelIdRemote = config.model.trim().ifBlank { DEFAULT_MODEL },
            reasoningEffort = null,
            customJsonOverride = """{"thinking":{"type":"disabled"}}""",
            displayName = "DeepSeek",
            maxTokens = 512,
        )
        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = messages,
            tools = emptyList(),
            sessionId = SESSION_ID,
        )
        val reply = runCatching {
            withTimeout(REQUEST_TIMEOUT_MS) {
                var text: String? = null
                ModelProviderManager.clientFor(provider).stream(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Completed -> text = event.message.content
                        is LlmStreamEvent.Failed -> throw event.error
                        else -> Unit
                    }
                }
                text
            }
        }.onFailure {
            WeLogger.w(TAG, "DeepSeek chat request failed", it)
        }.getOrNull()
        return reply?.let(::sanitize)
    }

    private fun sanitize(raw: String): String? {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val cleaned = line
            .removePrefix("我：")
            .removePrefix("AI：")
            .trim('"', '\'', '“', '”', '‘', '’', '「', '」', '《', '》')
        return cleaned.take(MAX_REPLY_CHARS).takeIf { it.isNotBlank() }
    }

    private fun groupSenderOf(content: String): String =
        GROUP_SENDER_REGEX.find(content)?.groupValues?.get(1).orEmpty()

    /** 只保留最近 [MAX_DEDUP_KEYS] 条去重键, 防止宿主重复插入同一行。 */
    private fun markSeen(key: String): Boolean = synchronized(dedupKeys) {
        if (dedupKeys.containsKey(key)) return false
        if (dedupKeys.size >= MAX_DEDUP_KEYS) {
            dedupKeys.entries.iterator().run {
                next()
                remove()
            }
        }
        dedupKeys[key] = System.currentTimeMillis()
        true
    }

    private fun nextGeneration(talker: String): Int =
        generations.compute(talker) { _, value -> (value ?: 0) + 1 }!!

    private fun withinBubbleWindow(): Boolean {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val start = bubbleStartMinute.coerceIn(0, 1439)
        val end = bubbleEndMinute.coerceIn(0, 1439)
        return if (start <= end) minute in start..end else minute >= start || minute <= end
    }

    private fun nextBubbleIntervalMillis(): Long {
        val min = bubbleMinMinutes.coerceAtLeast(1)
        val max = bubbleMaxMinutes.coerceAtLeast(min)
        return Random.nextLong(min.toLong(), max.toLong() + 1) * 60_000L
    }

    private fun snapshot(): Config = Config(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
        persona = persona,
        contextCount = contextCount.coerceIn(0, 100),
        mentionEnabled = mentionEnabled,
        mentionPercent = mentionPercent,
    )

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Config(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val persona: String,
        val contextCount: Int,
        val mentionEnabled: Boolean,
        val mentionPercent: Int,
    )

    override fun onClick(context: ComponentActivity) = showAiChatDialog(context)

    private val GROUP_SENDER_REGEX = Regex("""^([^:\n]+):\n""")

    /** 群聊里让指定成员收到"有人@我"提醒, 气泡内容本身保持原文。 */
    private fun atMsgSource(wxId: String): String =
        """<msgsource><atuserlist><![CDATA[$wxId]]></atuserlist></msgsource>"""

    private const val DEFAULT_PERSONA: String =
        "你是一个真实、随和、有点幽默的人，平时在微信里和朋友聊天。说话自然口语化，" +
                "会接梗，也会关心别人，偶尔用表情。你会认真看上下文，不会答非所问。"
}
