package com.xiao.feng.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.data.JsonDataMigration
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.dexkit.abc.IResolveDex
import com.xiao.feng.dexkit.dsl.dexMethod
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.ChatroomSyncStateReadResult
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.chat.ConversationAggregation
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookCallback
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.hookDirectly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.util.IdentityHashMap
import java.util.Locale

object AutoAddJoinedGroupToFolder : ClickableFeature(), IResolveDex {

    override val technicalId = "新进群自动归拢"
    override val nameRes = R.string.feature_auto_add_joined_group_folder_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_add_joined_group_folder_description

    private const val TAG = "AutoAddJoinedGroupToFolder"
    private const val MAX_SNAPSHOTS = 128

    private var folderId by prefOption("auto_add_joined_group_folder", "")

    private val methodSyncChatroomMembers by dexMethod()
    private val stateLock = Any()
    private val snapshots = IdentityHashMap<HookParam, JoinSnapshot>()
    private var scope = newScope()

    private data class JoinSnapshot(
        val roomId: String,
        val oldMemberIds: Set<String>,
        val selfWxId: String,
    )

    override fun resolveDex(dexKit: DexKitBridge) {
        val matches = dexKit.findMethod {
            matcher {
                returnType = "boolean"
                paramCount(10, 11)
                usingStrings("MicroMsg.ChatroomMembersLogic", "SyncAddChatroomMember")
            }
        }.filter { method ->
            val params = method.paramTypeNames
            params[0] == "java.lang.String" &&
                params[1] == "java.lang.String" &&
                params[3] == "int" &&
                params[4] == "int" &&
                params[5] == "int" &&
                params[6] == "java.lang.String" &&
                params[8] == "boolean" &&
                params[9] == "boolean" &&
                (params.size == 10 || params[10] == "int") &&
                params[2] !in PRIMITIVE_TYPE_NAMES &&
                params[7] !in PRIMITIVE_TYPE_NAMES
        }

        check(matches.size == 1) {
            "expected one ChatroomMembersLogic sync method, found ${matches.size}: " +
                matches.joinToString { it.descriptor }
        }
        methodSyncChatroomMembers.setDescriptor(matches.single())
    }

    override fun onEnable() {
        registerUnhook(methodSyncChatroomMembers.method.hookDirectly(object : HookCallback() {
            override fun beforeHookedMethod(param: HookParam) {
                val roomId = param.args[0] as String
                if (!roomId.isSupportedChatroomId()) return

                val selfWxId = WeApi.selfWxId
                if (selfWxId.isEmpty()) return

                val oldMemberIds = when (val result = WeDatabaseApi.getChatroomSyncState(roomId)) {
                    ChatroomSyncStateReadResult.MissingRow -> emptySet()
                    is ChatroomSyncStateReadResult.Available -> result.state.memberIds
                    ChatroomSyncStateReadResult.Unavailable -> return
                }
                synchronized(stateLock) {
                    if (snapshots.size >= MAX_SNAPSHOTS) snapshots.entries.iterator().run {
                        next()
                        remove()
                    }
                    snapshots[param] = JoinSnapshot(roomId, oldMemberIds, selfWxId)
                }
            }

            override fun afterHookedMethod(param: HookParam) {
                val snapshot = synchronized(stateLock) { snapshots.remove(param) } ?: return
                if (param.throwable != null) return
                if (WeApi.selfWxId != snapshot.selfWxId) return

                val newMemberIds = when (val result = WeDatabaseApi.getChatroomSyncState(snapshot.roomId)) {
                    ChatroomSyncStateReadResult.MissingRow -> return
                    is ChatroomSyncStateReadResult.Available -> result.state.memberIds
                    ChatroomSyncStateReadResult.Unavailable -> return
                }
                if (snapshot.selfWxId in snapshot.oldMemberIds) return
                if (snapshot.selfWxId !in newMemberIds) return

                submitAddToFolder(snapshot.roomId)
            }
        }))
    }

    override fun onDisable() {
        scope.cancel()
        synchronized(stateLock) { snapshots.clear() }
    }

    private fun submitAddToFolder(roomId: String) {
        val folderId = folderId
        if (folderId.isEmpty()) return
        if (!ConversationAggregation.isActive) {
            WeLogger.d(TAG, "skip $roomId: Conversation Aggregation is disabled")
            return
        }
        if (!JsonDataMigration.isCompleted("chat", "folders")) return
        scope.launch {
            try {
                val folder = ConversationAggregation.aggregationFolders().firstOrNull { it.id == folderId }
                if (folder == null || folder.isAuto) {
                    WeLogger.d(TAG, "skip $roomId: folder $folderId is unavailable")
                    return@launch
                }
                if (ConversationAggregation.addToFolder(folderId, roomId)) {
                    WeLogger.i(TAG, "added $roomId to folder ${folder.name}")
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                WeLogger.w(TAG, "failed to add $roomId to folder $folderId", error)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        val folders = if (
            ConversationAggregation.isActive && JsonDataMigration.isCompleted("chat", "folders")
        ) {
            ConversationAggregation.aggregationFolders().filterNot { it.isAuto }
        } else {
            emptyList()
        }
        showComposeDialog(context) {
            val localizedContext = LocalXiaoFengLocalizedContext.current
            var selectedId by mutableStateOf(folderId)

            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_auto_add_joined_group_folder_pick)) },
                text = {
                    if (folders.isEmpty()) {
                        Text(stringResource(R.string.chat_auto_add_joined_group_folder_none))
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(folders, key = { it.id }) { folder ->
                                RadioButtonWidget(
                                    title = folder.name,
                                    description = stringResource(R.string.chat_add_folder_manual_summary),
                                    selected = selectedId == folder.id,
                                    onClick = {
                                        selectedId = folder.id
                                        this@AutoAddJoinedGroupToFolder.folderId = folder.id
                                        showToast(
                                            context,
                                            localizedContext.getString(
                                                R.string.chat_auto_add_joined_group_folder_saved, folder.name
                                            )
                                        )
                                        onDismiss()
                                    },
                                    onSelect = {
                                        selectedId = folder.id
                                        this@AutoAddJoinedGroupToFolder.folderId = folder.id
                                        showToast(
                                            context,
                                            localizedContext.getString(
                                                R.string.chat_auto_add_joined_group_folder_saved, folder.name
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                }
            )
        }
    }

    private fun String.isSupportedChatroomId(): Boolean {
        val lowerCaseId = lowercase(Locale.ROOT)
        return lowerCaseId.endsWith("@chatroom") || lowerCaseId.endsWith("@im.chatroom")
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val PRIMITIVE_TYPE_NAMES = setOf(
        "boolean",
        "byte",
        "char",
        "double",
        "float",
        "int",
        "long",
        "short",
        "void",
        "java.lang.String",
    )
}
