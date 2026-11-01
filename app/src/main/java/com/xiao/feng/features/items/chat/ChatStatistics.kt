package com.xiao.feng.features.items.chat

import androidx.activity.ComponentActivity
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
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatStatisticsData(
    val contactCount: Int,
    val groupCount: Int,
    val topFriends: List<Pair<WeContact, Int>>,
)

private const val TOP_FRIEND_COUNT = 20

private fun loadChatStatistics(): ChatStatisticsData = ChatStatisticsData(
    contactCount = WeDatabaseApi.getContacts().size,
    groupCount = WeDatabaseApi.getGroups().size,
    topFriends = WeDatabaseApi.getFriendsOrderedByMessageCount(TOP_FRIEND_COUNT).toList(),
)

/** Read-only statistics panel: contact/group counts plus the most active private chats. */
object ChatStatistics : ClickableFeature() {

    override val technicalId = "聊天统计"
    override val nameRes = R.string.feature_chat_statistics_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_statistics_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) = showChatStatisticsDialog(context)
}

fun showChatStatisticsDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var data by remember { mutableStateOf<ChatStatisticsData?>(null) }

        LaunchedEffect(Unit) {
            data = withContext(Dispatchers.IO) { loadChatStatistics() }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.chat_statistics_title)) },
            text = {
                DefaultColumn {
                    val statistics = data
                    if (statistics == null) {
                        CircularProgressIndicator()
                    } else {
                        ListItem(
                            content = {
                                Text(stringResource(R.string.chat_statistics_contacts))
                            },
                            trailingContent = { Text(statistics.contactCount.toString()) },
                        )
                        ListItem(
                            content = {
                                Text(stringResource(R.string.chat_statistics_groups))
                            },
                            trailingContent = { Text(statistics.groupCount.toString()) },
                        )
                        Text(
                            stringResource(R.string.chat_statistics_top_friends),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (statistics.topFriends.isEmpty()) {
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
                                itemsIndexed(statistics.topFriends) { index, (contact, count) ->
                                    ListItem(
                                        content = {
                                            Text(
                                                stringResource(
                                                    R.string.chat_statistics_rank,
                                                    index + 1,
                                                    contact.displayName,
                                                )
                                            )
                                        },
                                        supportingContent = {
                                            Text(contact.wxId)
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
