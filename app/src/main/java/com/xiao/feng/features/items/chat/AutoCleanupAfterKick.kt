package com.xiao.feng.features.items.chat

import android.content.ContentValues
import com.xiao.feng.R
import com.xiao.feng.data.JsonDataMigration
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.chat.scheduledmessages.ScheduledMessageSchedule
import com.xiao.feng.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * Cleans XiaoFeng-owned references to a group after the user is removed from it:
 * scheduled messages that target the group lose that target (and are deleted when no target is
 * left), and the group is dropped from every manual Conversation Aggregation folder.
 *
 * Detected through the `chatroom` table update: WeChat writes the new `memberlist` when the server
 * sync removes the user, and the pre-update row still contains them, so self ∈ old and self ∉ new
 * is exactly "removed from this group".
 */
object AutoCleanupAfterKick : SwitchFeature(), WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "被踢自动清理"
    override val nameRes = R.string.feature_auto_cleanup_after_kick_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_cleanup_after_kick_description

    private const val TAG = "AutoCleanupAfterKick"
    private const val MAX_HANDLED_ROOMS = 128

    private val handledRooms = LinkedHashMap<String, Unit>(MAX_HANDLED_ROOMS, 0.75f, true)
    private var scope = newScope()

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
    }

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
        if (!wasMember || isMember) return
        if (!markHandled(roomId)) return

        submitCleanup(roomId)
    }

    private fun submitCleanup(roomId: String) {
        scope.launch {
            try {
                val before = ScheduledMessageSchedule.messages
                val after = ScheduledMessageSchedule.mutate { messages ->
                    messages.mapNotNull { message ->
                        val remaining = message.targets.filterNot { it.talker == roomId }
                        when {
                            remaining.isEmpty() -> null
                            remaining.size == message.targets.size -> message
                            else -> message.copy(targets = remaining)
                        }
                    }
                }
                val droppedMessages = before.size - after.size
                val removedFolders = if (JsonDataMigration.isCompleted("chat", "folders")) {
                    ConversationAggregation.removeFromAllManualFolders(roomId)
                } else {
                    0
                }
                WeLogger.i(
                    TAG,
                    "cleaned up kicked group $roomId: removed from $removedFolders folders, " +
                        "dropped $droppedMessages scheduled messages",
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                WeLogger.w(TAG, "failed to clean up kicked group $roomId", error)
            }
        }
    }

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

    private fun markHandled(roomId: String): Boolean = synchronized(handledRooms) {
        if (handledRooms.containsKey(roomId)) return@synchronized false
        if (handledRooms.size >= MAX_HANDLED_ROOMS) handledRooms.entries.iterator().run {
            next()
            remove()
        }
        handledRooms[roomId] = Unit
        true
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
