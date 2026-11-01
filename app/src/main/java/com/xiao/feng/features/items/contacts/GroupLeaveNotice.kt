package com.xiao.feng.features.items.contacts

import android.app.Activity
import android.content.ContentValues
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.ui.WeContactPrefsScreenApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.currentWxId
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/**
 * Per-group leave notice. The feature switch controls whether the entry is offered in group
 * settings; every group keeps its own enabled state and template.
 *
 * Template placeholders: `{member}` for the departed names, `{count}` for how many left,
 * `{group}` for the group display name.
 */
object GroupLeaveNotice : SwitchFeature(),
    WeContactPrefsScreenApi.IContactInfoProvider,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "退群提醒"
    override val nameRes = R.string.feature_group_leave_notice_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_group_leave_notice_description

    override val defaultEnabled = true

    private const val TAG = "GroupLeaveNotice"

    private const val KEY_ENABLED_PREFIX = "group_leave_enabled_"
    private const val KEY_TEMPLATE_PREFIX = "group_leave_template_"

    /** Guards against a batch removal (e.g. the group being dissolved) turning into message spam. */
    private const val MAX_NOTICE_MEMBERS = 10
    private const val SEND_DELAY_MILLIS = 1500L
    private const val MAX_DEDUP_KEYS = 256

    fun isEnabledForGroup(groupId: String): Boolean =
        KvStore.getBoolOrDef(KEY_ENABLED_PREFIX + groupId, false)

    fun templateForGroup(groupId: String): String =
        KvStore.getStringOrDef(KEY_TEMPLATE_PREFIX + groupId, "")

    fun saveGroupConfig(groupId: String, enabled: Boolean, template: String) {
        KvStore.putBool(KEY_ENABLED_PREFIX + groupId, enabled)
        KvStore.putString(KEY_TEMPLATE_PREFIX + groupId, template)
    }

    private var scope = newScope()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeContactPrefsScreenApi.addProvider(this)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        synchronized(dedupKeys) { dedupKeys.clear() }
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (activity.javaClass.name != ChatroomInfoUI::class.java.name) return emptyList()
        val groupId = activity.currentWxId ?: return emptyList()
        if (!groupId.isGroupChatWxId) return emptyList()

        val template = templateForGroup(groupId)
        val summaryRes = when {
            !isEnabledForGroup(groupId) -> R.string.group_leave_entry_disabled
            template.isBlank() -> R.string.group_leave_entry_unset
            else -> R.string.group_leave_entry_enabled
        }
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(R.string.feature_group_leave_notice_name),
                summary = activity.localizedContactsString(summaryRes),
                position = 2,
                onClick = onClick@{ itemActivity ->
                    showGroupLeaveNoticeDialog(itemActivity, groupId)
                },
            )
        )
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) {
        if (table != "chatroom") return

        val group = values.getAsString("chatroomname") ?: return
        if (!group.isGroupChatWxId) return
        if (!isEnabledForGroup(group)) return
        val template = templateForGroup(group).trim()
        if (template.isBlank()) return

        val newRawMembers = values.getAsString("memberlist") ?: return
        val newMemberCount = values.getAsInteger("memberCount") ?: return

        // The listener runs before the row is written, so this query still sees the old state.
        WeDatabaseApi.rawQuery(
            "SELECT memberlist,memberCount FROM chatroom WHERE chatroomname = ?",
            arrayOf(group),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return

            val oldRawMembers = cursor.getString(cursor.getColumnIndexOrThrow("memberlist")).orEmpty()
            val oldMemberCount = cursor.getInt(cursor.getColumnIndexOrThrow("memberCount"))
            // Nothing leaves, or the group was just created: no notice.
            if (oldMemberCount <= 1 || newMemberCount >= oldMemberCount) return

            val oldMembers = oldRawMembers.split(';').filter { it.isNotBlank() }.toSet()
            val newMembers = newRawMembers.split(';').filter { it.isNotBlank() }.toSet()
            val departed = oldMembers - newMembers
            if (departed.isEmpty()) return
            // If the current account is the one leaving, nobody is left to read the notice.
            val selfWxId = WeApi.selfWxId
            if (departed.contains(selfWxId)) return
            if (departed.size > MAX_NOTICE_MEMBERS) {
                WeLogger.d(TAG, "skip bulk leave of ${departed.size} member(s) in $group")
                return
            }

            val key = "$group|${departed.joinToString(",")}|$newMemberCount"
            if (!markDedup(key)) return

            val members = departed.toList()
            scope.launch {
                delay(SEND_DELAY_MILLIS)
                deliver(group, members, template)
            }
        }
    }

    private suspend fun deliver(group: String, members: List<String>, template: String) {
        val groupName = WeDatabaseApi.getDisplayName(group)
        val memberNames = members.joinToString("、") { member ->
            WeDatabaseApi.getGroupMemberDisplayName(group, member)
                .ifEmpty { WeDatabaseApi.getDisplayName(member) }
                .ifEmpty { member }
        }
        val text = template
            .replace("{member}", memberNames)
            .replace("{count}", members.size.toString())
            .replace("{group}", groupName)
        if (text.isBlank()) return

        val sent = runCatching { WeMessageApi.sendText(group, text) }.getOrDefault(false)
        WeLogger.i(TAG, "leave notice sent=$sent group=$group members=${members.size}")
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
