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
import androidx.compose.runtime.Composable
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class GroupChatStatistics(
    val memberCount: Int,
    val messageCount: Long,
    val todayMessages: Long,
    val weekMessages: Long,
    val sentMessages: Long,
    val lastMessageTime: Long,
    val typeCounts: Map<Int, Long>,
    val activeMembers: List<Pair<String, Int>>,
) {
    val receivedMessages: Long get() = messageCount - sentMessages
}

private const val TOP_MEMBER_COUNT = 20
private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

private fun Map<String, Any?>.asLong(key: String): Long =
    (this[key] as? Number)?.toLong() ?: 0L

private fun loadGroupStatistics(groupId: String): GroupChatStatistics {
    val memberCount = WeDatabaseApi.getGroupMembers(groupId).size

    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val weekStart = todayStart - 6 * DAY_MILLIS

    val summary = WeDatabaseApi.executeQuery(
        """SELECT COUNT(*) AS msg_count,
                  SUM(CASE WHEN isSend = 1 THEN 1 ELSE 0 END) AS sent_count,
                  SUM(CASE WHEN createTime >= ? THEN 1 ELSE 0 END) AS today_count,
                  SUM(CASE WHEN createTime >= ? THEN 1 ELSE 0 END) AS week_count,
                  MAX(createTime) AS last_time
           FROM message
           WHERE talker = ?""",
        arrayOf(todayStart, weekStart, groupId),
    ).firstOrNull()

    val typeRows = WeDatabaseApi.executeQuery(
        "SELECT type, COUNT(*) AS msg_count FROM message WHERE talker = ? GROUP BY type",
        arrayOf(groupId),
    )
    val typeCounts = typeRows.associate { row ->
        (row["type"] as Number).toInt() to row.asLong("msg_count")
    }

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
        val count = row.asLong("msg_count").toInt()
        val name = WeDatabaseApi.getGroupMemberDisplayName(groupId, sender)
            .ifEmpty { WeDatabaseApi.getDisplayName(sender) }
            .ifEmpty { sender }
        name to count
    }.filter { it.first.isNotBlank() }

    return GroupChatStatistics(
        memberCount = memberCount,
        messageCount = summary?.asLong("msg_count") ?: 0L,
        todayMessages = summary?.asLong("today_count") ?: 0L,
        weekMessages = summary?.asLong("week_count") ?: 0L,
        sentMessages = summary?.asLong("sent_count") ?: 0L,
        lastMessageTime = summary?.asLong("last_time") ?: 0L,
        typeCounts = typeCounts,
        activeMembers = activeMembers,
    )
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
                position = 3,
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
        val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

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
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            item {
                                StatisticRow(R.string.chat_statistics_members, statistics.memberCount.toString())
                            }
                            item {
                                StatisticRow(
                                    R.string.chat_statistics_total_messages,
                                    statistics.messageCount.toString(),
                                )
                            }
                            item {
                                StatisticRow(
                                    R.string.chat_statistics_today_messages,
                                    statistics.todayMessages.toString(),
                                )
                            }
                            item {
                                StatisticRow(
                                    R.string.chat_statistics_week_messages,
                                    statistics.weekMessages.toString(),
                                )
                            }
                            item {
                                StatisticRow(
                                    R.string.chat_statistics_sent_messages,
                                    statistics.sentMessages.toString(),
                                )
                            }
                            item {
                                StatisticRow(
                                    R.string.chat_statistics_received_messages,
                                    statistics.receivedMessages.toString(),
                                )
                            }
                            item {
                                StatisticRow(
                                    R.string.chat_statistics_last_message,
                                    if (statistics.lastMessageTime > 0) {
                                        timeFormat.format(Date(statistics.lastMessageTime))
                                    } else {
                                        stringResource(R.string.chat_statistics_no_messages)
                                    },
                                )
                            }

                            item {
                                Text(
                                    stringResource(R.string.chat_statistics_message_types),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            item { TypeRow(R.string.message_type_text, statistics.typeCounts[1]) }
                            item { TypeRow(R.string.message_type_image, statistics.typeCounts[3]) }
                            item { TypeRow(R.string.message_type_voice, statistics.typeCounts[34]) }
                            item { TypeRow(R.string.message_type_video, statistics.typeCounts[43]) }
                            item {
                                val knownTypes = setOf(1, 3, 34, 43)
                                TypeRow(
                                    R.string.chat_statistics_type_other,
                                    statistics.typeCounts.filterKeys { it !in knownTypes }.values.sum(),
                                )
                            }

                            item {
                                Text(
                                    stringResource(R.string.chat_statistics_active_members),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            if (statistics.activeMembers.isEmpty()) {
                                item {
                                    Text(
                                        stringResource(R.string.chat_statistics_empty),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
        )
    }
}

@Composable
private fun StatisticRow(labelRes: Int, value: String) {
    ListItem(
        content = { Text(stringResource(labelRes)) },
        trailingContent = { Text(value) },
    )
}

@Composable
private fun TypeRow(labelRes: Int, count: Long?) {
    ListItem(
        content = { Text(stringResource(labelRes)) },
        trailingContent = { Text((count ?: 0L).toString()) },
    )
}
