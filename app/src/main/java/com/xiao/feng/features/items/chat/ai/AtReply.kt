package com.xiao.feng.features.items.chat.ai

import android.content.ContentValues
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.system.keepalive.WeChatKeepAlive
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    /**
     * 宿主在后台时, 新消息由 :push 进程接收并写入消息库; 只在主进程挂钩会完全漏掉后台的 @。
     */
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    override val shouldEnableOnStartup: Boolean
        get() = isEnabled || alwaysEnabled || TargetProcesses.currentType == TargetProcess.PUSH

    private const val TAG = "AtReply"

    private const val KEY_TARGETS = "at_reply_targets"
    private const val KEY_DELAY = "at_reply_delay_seconds"
    private const val KEY_PERSONA = "at_reply_persona"
    private const val KEY_AUTO_ADD_NEW_GROUPS = "at_reply_auto_add_new_groups"
    private const val KEY_EXCLUDED_TARGETS = "at_reply_excluded_targets"
    private const val KEY_KNOWN_GROUPS = "at_reply_known_groups"
    private const val KEY_KNOWN_GROUPS_BASELINE = "at_reply_known_groups_baseline_done"

    private const val TEXT_MESSAGE_TYPE = 1
    private const val REPLY_DEBOUNCE_MILLIS = 1_500L
    private const val MAX_DEDUP_KEYS = 256

    /** 生效群聊; 空集合表示全部群聊。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    /** 全部群聊模式下被单独关闭的群; 只在 [targets] 为空时生效。 */
    var excludedTargets: Set<String> by prefOption(KEY_EXCLUDED_TARGETS, emptySet<String>())

    var replyDelaySeconds: Int by prefOption(KEY_DELAY, 2)

    /** 单独的人设提示词; 留空表示沿用「AI 聊天」里的人格设定。 */
    var persona: String by prefOption(KEY_PERSONA, "")

    /** 加入新的群聊时, 自动把它加进生效群聊列表。列表为空(全部群)时不生效。 */
    var autoAddNewGroups: Boolean by prefOption(KEY_AUTO_ADD_NEW_GROUPS, false)

    /** 已经见过的群; 用于区分真正的「新群」与宿主对 rcontact 的更新。 */
    private var knownGroups: Set<String> by prefOption(KEY_KNOWN_GROUPS, emptySet<String>())

    private var knownGroupsBaselineDone: Boolean by prefOption(KEY_KNOWN_GROUPS_BASELINE, false)

    private var scope = newScope()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)

    override fun onClick(context: ComponentActivity) {
        showAtReplyDialog(context)
    }

    /** 该会话是否由「艾特回复」接管; AI 聊天据此避让, 避免一条消息回两遍。 */
    fun isActiveFor(talker: String): Boolean =
        replyEnabled() && AiChat.isConfigured() && isGroupEnabled(talker)

    /** 本群当前是否生效; 聊天菜单里的单群开关用它显示状态。 */
    fun isGroupEnabled(talker: String): Boolean =
        if (targets.isEmpty()) talker !in excludedTargets else talker in targets

    /** 聊天菜单长按: 只改本群的生效状态, 不影响其他群。 */
    fun setGroupEnabled(talker: String, enabled: Boolean) {
        if (!talker.isGroupChatWxId) return
        if (targets.isEmpty()) {
            excludedTargets = if (enabled) excludedTargets - talker else excludedTargets + talker
        } else {
            targets = if (enabled) targets + talker else targets - talker
        }
    }

    /** 把当前已有的群记为已知; 开启「新群自动加入」时调用, 避免把老群当成新群。 */
    fun markGroupsKnown(groupIds: Collection<String>) {
        if (groupIds.isEmpty()) return
        knownGroups = knownGroups + groupIds.filter { it.isGroupChatWxId }
    }

    /**
     * 首次运行时把宿主里已有的群全部记为已知。
     * 返回 false 表示当前进程无法完成基线 (非主进程或数据库未就绪), 调用方应跳过本次自动加入。
     */
    private fun ensureKnownGroupsBaseline(): Boolean {
        if (knownGroupsBaselineDone) return true
        if (!TargetProcesses.isInMain || !WeDatabaseApi.isReady) return false
        val groups = runCatching {
            runBlocking(Dispatchers.IO) { WeDatabaseApi.getGroups() }
        }.getOrElse {
            WeLogger.w(TAG, "failed to baseline known groups", it)
            return false
        }
        markGroupsKnown(groups.map { it.wxId })
        knownGroupsBaselineDone = true
        WeLogger.i(TAG, "known group baseline: ${knownGroups.size}")
        return true
    }

    /**
     * 开关的持久化值。:push 进程常驻, 每次消息都重新读取 (KvStore 自带 1 秒缓存),
     * 以便感知主进程里的开关变化。
     */
    private fun replyEnabled(): Boolean = KvStore.getBoolOrDef(technicalId, defaultEnabled)

    override fun onEnable() {
        if (TargetProcesses.isInMain) WeChatKeepAlive.ensureEnabled()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        scope = newScope()
        synchronized(dedupKeys) { dedupKeys.clear() }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "rcontact") {
            handleNewContact(values)
            return
        }
        if (table != "message" || !replyEnabled()) return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return
        if ((values.getAsInteger("type") ?: return) != TEXT_MESSAGE_TYPE) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.isGroupChatWxId) return
        if (!isGroupEnabled(talker)) return

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
                AiChat.requestReplyFor(talker, sender, msgId, text, persona)
            }.onFailure {
                WeLogger.w(TAG, "failed to generate reply for $talker", it)
            }.getOrNull() ?: return@launch

            val sent = runCatching {
                AiChat.sendQuotedOrMentioned(talker, sender, msgId, createTime, answer)
            }.getOrDefault(false)
            WeLogger.i(TAG, "at reply talker=$talker sender=$sender sent=$sent")
        }
    }

    /**
     * 新加入的群聊会以一条 rcontact 记录出现。只在已经选了生效群聊(非全部)时才追加,
     * 否则空集合代表「全部群聊」, 追加一个反而把范围缩成一个群。
     */
    private fun handleNewContact(values: ContentValues) {
        if (!replyEnabled()) return
        val username = values.getAsString("username") ?: return
        if (!username.isGroupChatWxId) return
        if (!autoAddNewGroups) {
            // 顺手记住见过的群, 之后开启自动加入时不会把老群当成新群。
            if (username !in knownGroups) knownGroups = knownGroups + username
            return
        }
        // rcontact 的更新同样会走 insert 回调; 只有从未见过的群才算真正的新群,
        // 否则用户手动关掉的群会被「新群自动加入」反复加回生效列表。
        if (!ensureKnownGroupsBaseline()) return
        if (username in knownGroups) return
        knownGroups = knownGroups + username
        val current = targets
        if (current.isEmpty() || username in current) return
        targets = current + username
        WeLogger.i(TAG, "auto added new group to at-reply targets: $username")
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
