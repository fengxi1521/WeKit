package com.xiao.feng.features.items.chat_input_bar_menu

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Groups
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.contacts.ContactCollector
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天输入栏「+」菜单里的关系查找入口。
 *
 * - 群聊: 列出这个群里已经是好友的成员;
 * - 单聊: 列出自己和这位好友的共同群聊。
 */
object ChatRelationFinder : SwitchFeature() {

    override val technicalId = "群内好友与共同群聊"
    override val nameRes = R.string.feature_chat_relation_finder_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_relation_finder_description
    override val defaultEnabled = true

    private data class RelationResult(
        val title: String,
        val emptyRes: Int,
        val contacts: List<IWeContact>,
    )

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "chat_relation_finder",
                icon = MaterialSymbols.Outlined.Groups,
                label = localizedChatInputString(R.string.chat_relation_action_label),
                isSupported = { _, _ ->
                    val talker = WeCurrentConversationApi.value
                    talker.isGroupChatWxId || WeDatabaseApi.getFriend(talker) != null
                },
                onClick = { context, _ -> showRelationDialog(context) },
            ),
        )
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(provider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
    }

    private fun loadRelation(talker: String): RelationResult = if (talker.isGroupChatWxId) {
        val friends = ContactCollector.friendMembersOf(talker)
        RelationResult(
            title = localizedChatInputString(
                R.string.chat_relation_group_friends_title,
                friends.size,
            ),
            emptyRes = R.string.chat_relation_empty_friends,
            contacts = friends,
        )
    } else {
        val groups = WeDatabaseApi.getGroupsContainingMember(talker)
        RelationResult(
            title = localizedChatInputString(
                R.string.chat_relation_common_groups_title,
                WeDatabaseApi.getDisplayName(talker),
                groups.size,
            ),
            emptyRes = R.string.chat_relation_empty_groups,
            contacts = groups,
        )
    }

    private fun showRelationDialog(context: Context) {
        val talker = WeCurrentConversationApi.value

        showComposeDialog(context) {
            var loading by remember { mutableStateOf(true) }
            var result by remember { mutableStateOf<RelationResult?>(null) }

            LaunchedEffect(talker) {
                result = withContext(Dispatchers.IO) { loadRelation(talker) }
                loading = false
            }

            AlertDialogContent(
                title = {
                    Text(result?.title ?: stringResource(R.string.feature_chat_relation_finder_name))
                },
                text = {
                    val current = result
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.chat_relation_loading))
                        }

                        current == null || current.contacts.isEmpty() -> Text(
                            stringResource(current?.emptyRes ?: R.string.chat_relation_empty_friends)
                        )

                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(current.contacts, key = { it.wxId }) { contact ->
                                RelationRow(contact)
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    @Composable
    private fun RelationRow(contact: IWeContact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = contact.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    contact.wxId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
