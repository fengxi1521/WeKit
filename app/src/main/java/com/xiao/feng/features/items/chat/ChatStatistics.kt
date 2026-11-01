package com.xiao.feng.features.items.chat

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.ui.WeContactPrefsScreenApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.contacts.localizedContactsString
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.currentWxId
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GroupChatStatistics(
    val memberCount: Int,
    val messageCount: Long,
    val activeMembers: List<Pair<String, Int>>,
)

private const val TOP_MEMBER_COUNT = 20

private fun loadGroupStatistics(groupId: String): GroupChatStatistics {
    val memberCount = WeDatabaseApi.getGroupMembers(groupId).size
    val messageCount = WeDatabaseApi.executeQuery(
        "SELECT COUNT(*) AS msg_count FROM message WHERE talker = ?",
        arrayOf(groupId),
    ).firstOrNull()?.let { it["msg_count"] as Long } ?: 0L
    val senders = WeDatabaseApi.executeQuery(
        """SELECT substr(content, 1, instr(content, ':') - 1) AS sender, COUNT(*) AS msg_count
           FROM message
           WHERE talker = ? AND isSend = 0 AND instr(content, ':') > 0
           GROUP BY sender
           ORDER BY msg_count DESC
           LIMIT ?""",
        arrayOf(groupId, TOP_MEMBER_COUNT.toString()),
    )
    val activeMembers = senders.map { row ->
        val sender = row["sender"] as String
        val count = (row["msg_count"] as Long).toInt()
        val name = WeDatabaseApi.getGroupMemberDisplayName(groupId, sender)
            .ifEmpty { WeDatabaseApi.getDisplayName(sender) }
            .ifEmpty { sender }
        name to count
    }.filter { it.first.isNotBlank() }

    return GroupChatStatistics(memberCount, messageCount, activeMembers)
}

/** Per-group statistics entry inside the group settings screen. */
object ChatStatistics : SwitchFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "聊天统计"
    override val nameRes = R.string.feature_chat_statistics_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_chat_statistics_description

    override val defaultEnabled = true

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (activity.javaClass.name != ChatroomInfoUI::class.java.name) return emptyList()
        val groupId = activity.currentWxId ?: return emptyList()
        if (!groupId.isGroupChatWxId) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(R.string.feature_chat_statistics_name),
                summary = activity.localizedContactsString(R.string.chat_statistics_entry_summary),
                position = 1,
                onClick = onClick@{ itemActivity ->
                    showChatStatisticsDialog(itemActivity, groupId)
                },
            )
        )
    }
}

fun showChatStatisticsDialog(context: Context, groupId: String) {
    showComposeDialog(context) {
        var data by remember(groupId) { mutableStateOf<GroupChatStatistics?>(null) }
        var groupName by remember(groupId) { mutableStateOf("") }

        LaunchedEffect(groupId) {
            groupName = withContext(Dispatchers.IO) { WeDatabaseApi.getDisplayName(groupId) }
            data = withContext(Dispatchers.IO) { loadGroupStatistics(groupId) }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text(
                    if (groupName.isBlank()) {
                        stringResource(R.string.chat_statistics_title)
                    } else {
                        stringResource(R.string.chat_statistics_dialog_title_group, groupName)
                    }
                )
            },
            text = {
                DefaultColumn {
                    val statistics = data
                    if (statistics == null) {
                        CircularProgressIndicator()
                    } else {
                        ListItem(
                            content = {
                                Text(stringResource(R.string.chat_statistics_members))
                            },
                            trailingContent = { Text(statistics.memberCount.toString()) },
                        )
                        ListItem(
                            content = {
                                Text(stringResource(R.string.chat_statistics_total_messages))
                            },
                            trailingContent = { Text(statistics.messageCount.toString()) },
                        )
                        Text(
                            stringResource(R.string.chat_statistics_active_members),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (statistics.activeMembers.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_statistics_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp),
                            ) {
                                itemsIndexed(statistics.activeMembers) { index, (name, count) ->
                                    ListItem(
                                        content = {
                                            Text(
                                                stringResource(
                                                    R.string.chat_statistics_rank,
                                                    index + 1,
                                                    name,
                                                )
                                            )
                                        },
                                        trailingContent = {
                                            Text(
                                                stringResource(
                                                    R.string.chat_statistics_message_count,
                                                    count,
                                                )
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
        )
    }
}
