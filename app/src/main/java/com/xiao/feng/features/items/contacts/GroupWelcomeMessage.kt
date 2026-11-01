package com.xiao.feng.features.items.contacts

import android.content.ContentValues
import android.app.Activity
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
 * Per-group join welcome. The feature switch controls whether the entry is offered in group
 * settings; every group keeps its own enabled state and template.
 *
 * Template placeholders: `{member}` for the joined names, `{group}` for the group display name.
 */
object GroupWelcomeMessage : SwitchFeature(),
    WeContactPrefsScreenApi.IContactInfoProvider,
    WeDatabaseListenerApi.IUpdateListener {

    override val technicalId = "进群欢迎语"
    override val nameRes = R.string.feature_group_welcome_message_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_group_welcome_message_description

    override val defaultEnabled = true

    private const val TAG = "GroupWelcomeMessage"

    private const val KEY_ENABLED_PREFIX = "group_welcome_enabled_"
    private const val KEY_TEMPLATE_PREFIX = "group_welcome_template_"

    /** Guards against a full re-sync (or a large batch add) turning into message spam. */
    private const val MAX_WELCOME_MEMBERS = 10
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
            !isEnabledForGroup(groupId) -> R.string.group_welcome_entry_disabled
            template.isBlank() -> R.string.group_welcome_entry_unset
            else -> R.string.group_welcome_entry_enabled
        }
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(R.string.feature_group_welcome_message_name),
                summary = activity.localizedContactsString(summaryRes),
                position = 1,
                onClick = onClick@{ itemActivity ->
                    showGroupWelcomeMessageDialog(itemActivity, groupId)
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
            // A brand-new group starts at 1..2 members (self, maybe the creator); those are not joins.
            if (oldMemberCount <= 1 || newMemberCount <= oldMemberCount) return

            val oldMembers = oldRawMembers.split(';').filter { it.isNotBlank() }.toSet()
            val added = newRawMembers.split(';').filter { it.isNotBlank() }.toSet() - oldMembers
            val selfWxId = WeApi.selfWxId
            val welcomeMembers = added.filterNot { it == selfWxId }
            if (welcomeMembers.isEmpty()) return
            if (welcomeMembers.size > MAX_WELCOME_MEMBERS) {
                WeLogger.d(TAG, "skip bulk join of ${welcomeMembers.size} member(s) in $group")
                return
            }

            val key = "$group|${welcomeMembers.joinToString(",")}|$newMemberCount"
            if (!markDedup(key)) return

            val members = welcomeMembers.toList()
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
        }
        val text = template
            .replace("{member}", memberNames)
            .replace("{group}", groupName)
        if (text.isBlank()) return

        val sent = runCatching { WeMessageApi.sendText(group, text) }.getOrDefault(false)
        WeLogger.i(TAG, "welcome sent=$sent group=$group members=${members.size}")
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
