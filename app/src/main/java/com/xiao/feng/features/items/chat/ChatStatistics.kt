package com.xiao.feng.features.items.chat

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.ui.WeContactPrefsScreenApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.contacts.localizedContactsString
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.currentWxId
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class StatsPage { SUMMARY, RANKING, LOW_ACTIVITY, MEMBER }

data class MemberActivity(
    val wxid: String,
    val name: String,
    val count: Int,
    val isSelf: Boolean = false,
)

data class MemberMessage(
    val createTime: Long,
    val type: Int,
    val text: String,
)

data class GroupChatStatistics(
    val memberCount: Int,
    val messageCount: Long,
    val sentMessages: Long,
    val activeMemberCount: Int,
    val lastMessageTime: Long,
    val typeCounts: Map<Int, Long>,
    val members: List<MemberActivity>,
) {
    val receivedMessages: Long get() = messageCount - sentMessages
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
private const val MESSAGE_PAGE_SIZE = 100
private const val TEXT_MESSAGE_TYPE = 1
private const val LOW_ACTIVITY_DEFAULT_THRESHOLD = 5
private const val LOW_ACTIVITY_MAX_THRESHOLD = 50
private const val DEFAULT_RANGE_DAYS = 365
private const val MIN_RANGE_DAYS = 1
private const val MAX_RANGE_DAYS = 365

private fun Map<String, Any?>.asLong(key: String): Long =
    (this[key] as? Number)?.toLong() ?: 0L

private fun Map<String, Any?>.asInt(key: String): Int =
    (this[key] as? Number)?.toInt() ?: 0

private data class GroupMemberBase(val wxid: String, val name: String)

private fun loadGroupMembers(groupId: String): List<GroupMemberBase> {
    val displayNames = WeDatabaseApi.getGroupMemberDisplayNames(groupId)
    return WeDatabaseApi.getGroupMembers(groupId).map { contact ->
        GroupMemberBase(
            wxid = contact.wxId,
            name = displayNames[contact.wxId].orEmpty()
                .ifEmpty { contact.remarkName }
                .ifEmpty { contact.nickname }
                .ifEmpty { contact.wxId },
        )
    }
}

private fun loadGroupStatistics(
    groupId: String,
    days: Int,
    baseMembers: List<GroupMemberBase>,
): GroupChatStatistics {
    val rangeStart = System.currentTimeMillis() - days * DAY_MILLIS

    val summary = WeDatabaseApi.executeQuery(
        """SELECT COUNT(*) AS msg_count,
                  SUM(CASE WHEN isSend = 1 THEN 1 ELSE 0 END) AS sent_count,
                  MAX(createTime) AS last_time
           FROM message
           WHERE talker = ? AND createTime >= ?""",
        arrayOf(groupId, rangeStart),
    ).firstOrNull()

    val typeRows = WeDatabaseApi.executeQuery(
        """SELECT type, COUNT(*) AS msg_count
           FROM message
           WHERE talker = ? AND createTime >= ?
           GROUP BY type""",
        arrayOf(groupId, rangeStart),
    )
    val typeCounts = typeRows.associate { row ->
        row.asInt("type") to row.asLong("msg_count")
    }

    val senderRows = WeDatabaseApi.executeQuery(
        """SELECT substr(content, 1, instr(content, ':') - 1) AS sender, COUNT(*) AS msg_count
           FROM message
           WHERE talker = ? AND isSend = 0 AND instr(content, ':') > 0 AND createTime >= ?
           GROUP BY sender""",
        arrayOf(groupId, rangeStart),
    )
    val senderCounts = senderRows.associate { row ->
        (row["sender"] as String) to row.asInt("msg_count")
    }

    val selfWxId = WeApi.selfWxId
    val sentMessages = summary?.asLong("sent_count") ?: 0L

    val members = baseMembers.map { member ->
        val isSelf = member.wxid == selfWxId
        val count = if (isSelf) sentMessages.toInt() else senderCounts[member.wxid] ?: 0
        MemberActivity(member.wxid, member.name, count, isSelf)
    }.toMutableList()

    // Keep members who already left so their history still shows up in the ranking.
    senderCounts.forEach { (wxid, count) ->
        if (wxid.isNotBlank() && members.none { it.wxid == wxid }) {
            val name = WeDatabaseApi.getGroupMemberDisplayName(groupId, wxid)
                .ifEmpty { WeDatabaseApi.getDisplayName(wxid) }
                .ifEmpty { wxid }
            members += MemberActivity(wxid, name, count)
        }
    }

    return GroupChatStatistics(
        memberCount = baseMembers.size,
        messageCount = summary?.asLong("msg_count") ?: 0L,
        sentMessages = sentMessages,
        activeMemberCount = senderCounts.size + if (sentMessages > 0) 1 else 0,
        lastMessageTime = summary?.asLong("last_time") ?: 0L,
        typeCounts = typeCounts,
        members = members,
    )
}

private fun loadMemberMessages(groupId: String, member: MemberActivity): List<MemberMessage> {
    val rows = if (member.isSelf) {
        WeDatabaseApi.executeQuery(
            """SELECT createTime, type, content FROM message
               WHERE talker = ? AND isSend = 1
               ORDER BY createTime DESC
               LIMIT ?""",
            arrayOf(groupId, MESSAGE_PAGE_SIZE.toString()),
        )
    } else {
        WeDatabaseApi.executeQuery(
            """SELECT createTime, type, content FROM message
               WHERE talker = ? AND isSend = 0 AND substr(content, 1, instr(content, ':') - 1) = ?
               ORDER BY createTime DESC
               LIMIT ?""",
            arrayOf(groupId, member.wxid, MESSAGE_PAGE_SIZE.toString()),
        )
    }

    return rows.map { row ->
        val type = row.asInt("type")
        val content = row["content"] as String
        val text = if (type == TEXT_MESSAGE_TYPE) {
            if (member.isSelf) content.trim() else content.substringAfter(':', "").trim()
        } else {
            ""
        }
        MemberMessage(row.asLong("createTime"), type, text)
    }
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
        var page by remember(groupId) { mutableStateOf(StatsPage.SUMMARY) }
        var selectedMember by remember(groupId) { mutableStateOf<MemberActivity?>(null) }
        var memberMessages by remember(groupId) { mutableStateOf<List<MemberMessage>?>(null) }
        var memberReturnPage by remember(groupId) { mutableStateOf(StatsPage.RANKING) }
        var descending by remember(groupId) { mutableStateOf(true) }
        var lowThreshold by remember(groupId) { mutableStateOf(LOW_ACTIVITY_DEFAULT_THRESHOLD) }
        var rangeDays by remember(groupId) { mutableStateOf(DEFAULT_RANGE_DAYS) }
        var baseMembers by remember(groupId) { mutableStateOf<List<GroupMemberBase>?>(null) }
        val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

        LaunchedEffect(groupId) {
            groupName = withContext(Dispatchers.IO) { WeDatabaseApi.getDisplayName(groupId) }
            baseMembers = withContext(Dispatchers.IO) { loadGroupMembers(groupId) }
        }

        LaunchedEffect(groupId, rangeDays, baseMembers) {
            val base = baseMembers ?: return@LaunchedEffect
            // Debounce so dragging the range slider does not trigger a query per step.
            if (data != null) delay(250)
            data = withContext(Dispatchers.IO) { loadGroupStatistics(groupId, rangeDays, base) }
        }

        LaunchedEffect(selectedMember) {
            val member = selectedMember ?: return@LaunchedEffect
            memberMessages = withContext(Dispatchers.IO) { loadMemberMessages(groupId, member) }
        }

        val titleText = when (page) {
            StatsPage.SUMMARY -> if (groupName.isBlank()) {
                stringResource(R.string.chat_statistics_title)
            } else {
                stringResource(R.string.chat_statistics_dialog_title_group, groupName)
            }

            StatsPage.RANKING -> stringResource(R.string.chat_statistics_ranking_title)
            StatsPage.LOW_ACTIVITY -> stringResource(R.string.chat_statistics_low_activity_title)
            StatsPage.MEMBER -> stringResource(
                R.string.chat_statistics_member_messages_title,
                selectedMember?.name.orEmpty(),
            )
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(titleText) },
            text = {
                DefaultColumn {
                    val statistics = data
                    if (statistics == null) {
                        CircularProgressIndicator()
                    } else {
                        when (page) {
                            StatsPage.SUMMARY -> SummaryPage(
                                statistics = statistics,
                                rangeDays = rangeDays,
                                onRangeChange = { rangeDays = it },
                                lastMessageText = if (statistics.lastMessageTime > 0) {
                                    timeFormat.format(Date(statistics.lastMessageTime))
                                } else {
                                    stringResource(R.string.chat_statistics_no_messages)
                                },
                                onOpenRanking = {
                                    selectedMember = null
                                    page = StatsPage.RANKING
                                },
                                onOpenLowActivity = {
                                    selectedMember = null
                                    page = StatsPage.LOW_ACTIVITY
                                },
                            )

                            StatsPage.RANKING -> RankingPage(
                                members = statistics.members,
                                descending = descending,
                                onSortChange = { descending = it },
                                onMemberClick = { member ->
                                    selectedMember = member
                                    memberReturnPage = StatsPage.RANKING
                                    page = StatsPage.MEMBER
                                },
                            )

                            StatsPage.LOW_ACTIVITY -> LowActivityPage(
                                members = statistics.members,
                                threshold = lowThreshold,
                                onThresholdChange = { lowThreshold = it },
                                onMemberClick = { member ->
                                    selectedMember = member
                                    memberReturnPage = StatsPage.LOW_ACTIVITY
                                    page = StatsPage.MEMBER
                                },
                            )

                            StatsPage.MEMBER -> MemberPage(
                                messages = memberMessages,
                                timeFormat = timeFormat,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
            dismissButton = if (page == StatsPage.SUMMARY) {
                null
            } else {
                {
                    TextButton(onClick = {
                        if (page == StatsPage.MEMBER) {
                            selectedMember = null
                            page = memberReturnPage
                        } else {
                            page = StatsPage.SUMMARY
                        }
                    }) {
                        Text(stringResource(R.string.chat_statistics_back))
                    }
                }
            },
        )
    }
}

@Composable
private fun SummaryPage(
    statistics: GroupChatStatistics,
    rangeDays: Int,
    onRangeChange: (Int) -> Unit,
    lastMessageText: String,
    onOpenRanking: () -> Unit,
    onOpenLowActivity: () -> Unit,
) {
    val textLabel = stringResource(R.string.message_type_text)
    val imageLabel = stringResource(R.string.message_type_image)
    val voiceLabel = stringResource(R.string.message_type_voice)
    val videoLabel = stringResource(R.string.message_type_video)
    val otherLabel = stringResource(R.string.chat_statistics_type_other)
    val knownTypes = setOf(1, 3, 34, 43)
    val otherCount = statistics.typeCounts.filterKeys { it !in knownTypes }.values.sum()
    val typeSummary = listOf(
        textLabel to (statistics.typeCounts[1] ?: 0L),
        imageLabel to (statistics.typeCounts[3] ?: 0L),
        voiceLabel to (statistics.typeCounts[34] ?: 0L),
        videoLabel to (statistics.typeCounts[43] ?: 0L),
        otherLabel to otherCount,
    ).filter { it.second > 0 }
        .joinToString(" · ") { (label, count) -> "$label $count" }
    val dailyAverage = statistics.messageCount / rangeDays

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            IntNumberPickerWidget(
                title = stringResource(R.string.chat_statistics_range),
                description = stringResource(R.string.chat_statistics_range_summary, rangeDays),
                value = rangeDays,
                startInt = MIN_RANGE_DAYS,
                endInt = MAX_RANGE_DAYS,
                stepSize = 1,
                valueSuffix = stringResource(R.string.chat_statistics_range_unit),
                onValueChange = onRangeChange,
            )
        }

        item { StatisticRow(stringResource(R.string.chat_statistics_members), statistics.memberCount.toString()) }
        item {
            StatisticRow(
                stringResource(R.string.chat_statistics_messages_in_range, rangeDays),
                statistics.messageCount.toString(),
            )
        }
        item { StatisticRow(stringResource(R.string.chat_statistics_sent_messages), statistics.sentMessages.toString()) }
        item {
            StatisticRow(
                stringResource(R.string.chat_statistics_received_messages),
                statistics.receivedMessages.toString(),
            )
        }
        item {
            StatisticRow(
                stringResource(R.string.chat_statistics_active_members),
                statistics.activeMemberCount.toString(),
            )
        }
        item { StatisticRow(stringResource(R.string.chat_statistics_daily_average), dailyAverage.toString()) }
        item { StatisticRow(stringResource(R.string.chat_statistics_last_message), lastMessageText) }

        item {
            Text(
                stringResource(R.string.chat_statistics_message_types),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        item {
            Text(
                text = typeSummary.ifBlank { stringResource(R.string.chat_statistics_empty) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenRanking,
                ) {
                    Text(stringResource(R.string.chat_statistics_ranking_title))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenLowActivity,
                ) {
                    Text(stringResource(R.string.chat_statistics_low_activity_title))
                }
            }
        }
    }
}

@Composable
private fun RankingPage(
    members: List<MemberActivity>,
    descending: Boolean,
    onSortChange: (Boolean) -> Unit,
    onMemberClick: (MemberActivity) -> Unit,
) {
    val spokenMembers = remember(members, descending) {
        val spoken = members.filter { it.count > 0 }
        if (descending) {
            spoken.sortedWith(compareByDescending<MemberActivity> { it.count }.thenBy { it.name })
        } else {
            spoken.sortedWith(compareBy<MemberActivity> { it.count }.thenBy { it.name })
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = descending,
                    onClick = { onSortChange(true) },
                    label = { Text(stringResource(R.string.chat_statistics_sort_desc)) },
                )
                FilterChip(
                    selected = !descending,
                    onClick = { onSortChange(false) },
                    label = { Text(stringResource(R.string.chat_statistics_sort_asc)) },
                )
            }
        }

        if (spokenMembers.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.chat_statistics_ranking_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(spokenMembers) { index, member ->
            MemberRow(
                member = member,
                leading = stringResource(R.string.chat_statistics_rank, index + 1, memberLabel(member)),
                onClick = { onMemberClick(member) },
            )
        }
    }
}

@Composable
private fun LowActivityPage(
    members: List<MemberActivity>,
    threshold: Int,
    onThresholdChange: (Int) -> Unit,
    onMemberClick: (MemberActivity) -> Unit,
) {
    val filtered = remember(members, threshold) {
        members.filter { it.count < threshold }.sortedWith(
            compareBy<MemberActivity> { it.count }.thenBy { it.name }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            IntNumberPickerWidget(
                title = stringResource(R.string.chat_statistics_low_activity_threshold),
                description = stringResource(R.string.chat_statistics_low_activity_count, filtered.size),
                value = threshold,
                startInt = 1,
                endInt = LOW_ACTIVITY_MAX_THRESHOLD,
                stepSize = 1,
                onValueChange = onThresholdChange,
            )
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.chat_statistics_low_activity_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(filtered) { member ->
            MemberRow(
                member = member,
                leading = memberLabel(member),
                onClick = { onMemberClick(member) },
            )
        }
    }
}

@Composable
private fun MemberPage(
    messages: List<MemberMessage>?,
    timeFormat: SimpleDateFormat,
) {
    if (messages == null) {
        CircularProgressIndicator()
        return
    }
    if (messages.isEmpty()) {
        Text(
            stringResource(R.string.chat_statistics_member_messages_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(messages) { message ->
            ListItem(
                content = {
                    Text(
                        text = if (message.text.isNotBlank()) {
                            message.text
                        } else {
                            messageTypeLabel(message.type)
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(timeFormat.format(Date(message.createTime)))
                },
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: MemberActivity,
    leading: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        content = { Text(leading) },
        trailingContent = {
            Text(stringResource(R.string.chat_statistics_message_count, member.count))
        },
    )
}

@Composable
private fun memberLabel(member: MemberActivity): String =
    if (member.isSelf) stringResource(R.string.chat_statistics_self) else member.name

@Composable
private fun messageTypeLabel(type: Int): String = when (type) {
    TEXT_MESSAGE_TYPE -> stringResource(R.string.message_type_text)
    3 -> stringResource(R.string.message_type_image)
    34 -> stringResource(R.string.message_type_voice)
    43 -> stringResource(R.string.message_type_video)
    else -> stringResource(R.string.chat_statistics_type_other)
}

@Composable
private fun StatisticRow(label: String, value: String) {
    ListItem(
        content = { Text(label) },
        trailingContent = { Text(value) },
    )
}
