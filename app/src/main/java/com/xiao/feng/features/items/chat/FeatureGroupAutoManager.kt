package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.chat.ai.AiChat
import com.xiao.feng.features.items.chat.ai.AtReply
import com.xiao.feng.features.items.chat.scheduledmessages.ScheduledMessageSchedule
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 「进群自动启用」统一入口: 用户选好哪些功能要自动应用, 加入新群/被拉进已有群时
 * 自动把该群加入各功能的生效范围; 退群或被踢时自动清理。
 *
 * 检测方式:
 * - 新群创建 / 被拉入已有群 → rcontact INSERT (username 是 @chatroom)
 *   或 chatroom UPDATE (memberlist 新增了自己)
 * - 退群 / 被踢 → chatroom UPDATE (memberlist 移除了自己)
 *
 * 通过 knownGroups 基线区分真正的新群与宿主对 rcontact 的常规更新,
 * 避免把老群反复当成新群。
 */
object FeatureGroupAutoManager : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "进群自动启用"
    override val nameRes = R.string.feature_group_auto_manager_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_auto_manager_description

    /** push 进程也可能收到 rcontact / chatroom 变更, 两个进程都挂上监听。 */
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val TAG = "GroupAutoMgr"

    private const val KEY_POOL = "group_auto_pool"
    private const val KEY_KNOWN_GROUPS = "group_auto_known_groups"
    private const val KEY_KNOWN_GROUPS_BASELINE_DONE = "group_auto_known_baseline_done"
    private const val KEY_HANDLED_ROOMS = "group_auto_handled_rooms"

    /**
     * 进群时要自动启用的功能 technicalId 集合。
     * 每条必须在 [SUPPORTED_FEATURES] 里。
     */
    var pool: Set<String> by prefOption(KEY_POOL, emptySet<String>())

    /** 已经见过的群; 用来区分真正的「新群」与宿主对 rcontact 的常规更新。 */
    private var knownGroups: Set<String> by prefOption(KEY_KNOWN_GROUPS, emptySet<String>())

    private var knownGroupsBaselineDone: Boolean by prefOption(KEY_KNOWN_GROUPS_BASELINE_DONE, false)

    /** 退群清理去重: 同一会话的 chatroom UPDATE 可能触发多次。 */
    private var handledRooms: Set<String> by prefOption(KEY_HANDLED_ROOMS, emptySet<String>())

    private var scope: CoroutineScope? = null

    // ==================== 进群 / 退群 分发 ====================

    /** 池内可注册的功能 technicalId 常量 —— 让调用方和 UI 都用同一个值。 */
    object SupportedTechnicalIds {
        const val AT_REPLY = "艾特回复"
        const val AI_CHAT = "AI 聊天"
        const val AUTO_PAT_BACK = "自动回拍"
        const val AUTO_REPEAT = "自动复读"
        const val QQ_MUSIC_ORDER = "QQ 点歌"
        const val SCHEDULED_MESSAGES = "定时消息"
    }

    /** 所有支持「进群自动启用」的功能元数据; UI 直接用它渲染选择列表。 */
    val SUPPORTED_FEATURES: List<GroupAutoFeature> = listOf(
        GroupAutoFeature(
            technicalId = SupportedTechnicalIds.AT_REPLY,
            nameRes = R.string.feature_at_reply_name,
            addToGroup = { groupId -> AtReply.addToGroup(groupId) },
            removeFromGroup = { groupId -> AtReply.removeFromGroup(groupId) },
        ),
        GroupAutoFeature(
            technicalId = SupportedTechnicalIds.AI_CHAT,
            nameRes = R.string.feature_ai_chat_name,
            addToGroup = { groupId -> AiChat.addToGroup(groupId) },
            removeFromGroup = { groupId -> AiChat.removeFromGroup(groupId) },
        ),
        GroupAutoFeature(
            technicalId = SupportedTechnicalIds.AUTO_PAT_BACK,
            nameRes = R.string.feature_auto_pat_back_name,
            addToGroup = { groupId -> AutoPatBack.addToGroup(groupId) },
            removeFromGroup = { groupId -> AutoPatBack.removeFromGroup(groupId) },
        ),
        GroupAutoFeature(
            technicalId = SupportedTechnicalIds.AUTO_REPEAT,
            nameRes = R.string.feature_auto_repeat_name,
            addToGroup = { groupId -> AutoRepeat.addToGroup(groupId) },
            removeFromGroup = { groupId -> AutoRepeat.removeFromGroup(groupId) },
        ),
        GroupAutoFeature(
            technicalId = SupportedTechnicalIds.QQ_MUSIC_ORDER,
            nameRes = R.string.feature_qq_music_order_name,
            addToGroup = { groupId -> QqMusicOrder.addToGroup(groupId) },
            removeFromGroup = { groupId -> QqMusicOrder.removeFromGroup(groupId) },
        ),
        GroupAutoFeature(
            technicalId = SupportedTechnicalIds.SCHEDULED_MESSAGES,
            nameRes = R.string.feature_scheduled_messages_name,
            addToGroup = { /* 定时消息没有全局 targets, 跳过 */ },
            removeFromGroup = { groupId -> ScheduledMessageSchedule.removeGroupFromAll(groupId) },
        ),
    )

    // ==================== 生命周期 ====================

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope?.cancel()
        scope = null
    }

    override fun onClick(context: ComponentActivity) = showPoolDialog(context)

    // ==================== IInsertListener (检测新群 / 加入已有群) ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "rcontact") return
        val username = values.getAsString("username") ?: return
        if (!username.isGroupChatWxId) return
        if (!shouldActNow()) return
        if (pool.isEmpty()) return
        handleNewGroup(username)
    }

    // ==================== IUpdateListener (检测进群 / 退群) ====================

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) {
        if (table != "chatroom") return
        val roomId = values.getAsString("chatroomname") ?: return
        val newRawMembers = values.getAsString("memberlist") ?: return
        val selfWxId = WeApi.selfWxId
        if (selfWxId.isEmpty()) return

        val oldRawMembers = readStoredMemberList(roomId) ?: return
        val wasMember = selfWxId in oldRawMembers.memberSet()
        val isMember = selfWxId in newRawMembers.memberSet()

        when {
            // 新加入已有群: 之前不在, 现在在
            !wasMember && isMember -> {
                if (!shouldActNow()) return
                if (pool.isEmpty()) return
                handleNewGroup(roomId)
            }
            // 退群 / 被踢: 之前在, 现在不在
            wasMember && !isMember -> {
                if (!markHandled(roomId)) return
                scope?.launch {
                    applyRemoveFromGroup(roomId)
                }
            }
        }
    }

    // ==================== 内部实现 ====================

    private fun shouldActNow(): Boolean =
        isEnabled &&
            TargetProcesses.currentType in targetProcesses &&
            selfWxIdReady()

    private fun selfWxIdReady(): Boolean = WeApi.selfWxId.isNotEmpty()

    /** 首次运行时把宿主里已有的群全部记为已知, 避免把老群当成新群。 */
    private fun ensureKnownGroupsBaseline(): Boolean {
        if (knownGroupsBaselineDone) return true
        if (!TargetProcesses.isInMain || !WeDatabaseApi.isReady) return false
        val groups = runCatching {
            runBlocking(Dispatchers.IO) { WeDatabaseApi.getGroups() }
        }.getOrElse {
            WeLogger.w(TAG, "failed to baseline known groups", it)
            return false
        }
        knownGroups = knownGroups + groups.map { it.wxId }
        knownGroupsBaselineDone = true
        WeLogger.i(TAG, "known group baseline: ${knownGroups.size}")
        return true
    }

    private fun handleNewGroup(groupId: String) {
        if (!ensureKnownGroupsBaseline()) return
        // 已经见过的群不再触发 —— 避免宿主常规更新 rcontact 时反复加
        if (groupId in knownGroups) return
        knownGroups = knownGroups + groupId
        scope?.launch {
            applyAddToGroup(groupId)
        }
    }

    private fun applyAddToGroup(groupId: String) {
        val activeFeatures = SUPPORTED_FEATURES.filter { it.technicalId in pool }
        if (activeFeatures.isEmpty()) return
        activeFeatures.forEach { feature ->
            runCatching { feature.addToGroup(groupId) }.onFailure { e ->
                WeLogger.w(TAG, "failed to add group $groupId to ${feature.technicalId}", e)
            }
        }
        WeLogger.i(
            TAG,
            "auto-enabled pool features for new group $groupId: " +
                activeFeatures.joinToString { it.technicalId },
        )
    }

    private fun applyRemoveFromGroup(groupId: String) {
        val activeFeatures = SUPPORTED_FEATURES.filter { it.technicalId in pool }
        if (activeFeatures.isEmpty()) return
        activeFeatures.forEach { feature ->
            runCatching { feature.removeFromGroup(groupId) }.onFailure { e ->
                WeLogger.w(TAG, "failed to remove group $groupId from ${feature.technicalId}", e)
            }
        }
        WeLogger.i(
            TAG,
            "auto-cleaned pool features for group $groupId: " +
                activeFeatures.joinToString { it.technicalId },
        )
    }

    // ==================== 与 AutoCleanupAfterKick 共用的辅助 ====================

    private fun readStoredMemberList(roomId: String): String? = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT memberlist FROM chatroom WHERE chatroomname = ?",
            arrayOf(roomId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(cursor.getColumnIndex("memberlist"))
        }
    }.onFailure {
        WeLogger.w(TAG, "failed to read stored memberlist for $roomId", it)
    }.getOrNull()

    private fun String.memberSet(): Set<String> =
        split(';').asSequence().map(String::trim).filter(String::isNotEmpty).toSet()

    private fun markHandled(roomId: String): Boolean {
        if (roomId in handledRooms) return false
        // 退群清理去重集合只保留最近 128 条, 避免无限增长
        if (handledRooms.size >= 128) {
            handledRooms = handledRooms.drop(handledRooms.size - 127).toSet()
        }
        handledRooms = handledRooms + roomId
        return true
    }
}

/** 单个可加入「进群自动启用池」的功能元数据。 */
data class GroupAutoFeature(
    val technicalId: String,
    @androidx.annotation.StringRes val nameRes: Int,
    val addToGroup: (String) -> Unit,
    val removeFromGroup: (String) -> Unit,
)

// ==================== 设置对话框 ====================

private fun showPoolDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var pool by remember { mutableStateOf(FeatureGroupAutoManager.pool) }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.feature_group_auto_manager_name)) },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.feature_group_auto_manager_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.group_auto_pool_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(FeatureGroupAutoManager.SUPPORTED_FEATURES) { feature ->
                            val selected = feature.technicalId in pool
                            ListItem(
                                modifier = Modifier.clickable {
                                    pool = if (selected) pool - feature.technicalId else pool + feature.technicalId
                                },
                                content = { Text(stringResource(feature.nameRes)) },
                                trailingContent = {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = null,
                                    )
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    FeatureGroupAutoManager.pool = pool
                    onDismiss()
                }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = {
                Button(onClick = { onDismiss() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}
