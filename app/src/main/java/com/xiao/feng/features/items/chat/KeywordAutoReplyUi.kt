package com.xiao.feng.features.items.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.features.items.chat.panel.pickPanelFile
import com.xiao.feng.features.items.chat.scheduledmessages.ScheduledMessageContent
import com.xiao.feng.features.items.chat.scheduledmessages.ScheduledMessageContentType
import com.xiao.feng.features.items.chat.scheduledmessages.contentItemTypeLabel
import com.xiao.feng.features.items.chat.scheduledmessages.importScheduledFile
import com.xiao.feng.features.items.chat.scheduledmessages.pickMimeTypes
import com.xiao.feng.features.items.chat.scheduledmessages.usesTextField
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.IconButton
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.DropDownMenuWidget
import com.xiao.feng.ui.content.m3.DropdownOption
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun showKeywordAutoReplyDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        val rules = remember { KeywordAutoReply.rules.toMutableStateList() }
        var targets by remember { mutableStateOf(KeywordAutoReply.targets) }
        var contacts by remember { mutableStateOf<List<IWeContact>>(emptyList()) }
        LaunchedEffect(Unit) {
            contacts = withContext(Dispatchers.IO) {
                if (!WeDatabaseApi.isReady) return@withContext emptyList<IWeContact>()
                runCatching {
                    buildList {
                        addAll(WeDatabaseApi.getFriends())
                        addAll(WeDatabaseApi.getGroups())
                    }
                }.getOrDefault(emptyList())
            }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.keyword_auto_reply_title)) },
            text = {
                DefaultColumn {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.keyword_auto_reply_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                showAutoReplyRuleEditor(
                                    context = context,
                                    titleRes = R.string.keyword_auto_reply_add_title,
                                    initial = AutoReplyRule(id = newAutoReplyRuleId()),
                                    contacts = contacts,
                                ) { added -> rules.add(added) }
                            }
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.keyword_auto_reply_add))
                        }
                    }

                    ListItem(
                        modifier = Modifier.clickable {
                            showComposeDialog(context) {
                                ContactsSelector(
                                    title = stringResource(R.string.keyword_auto_reply_targets_select),
                                    contacts = contacts,
                                    initialSelectedWxIds = targets,
                                    onDismiss = this.onDismiss,
                                    onConfirm = { selected ->
                                        targets = selected
                                        KeywordAutoReply.targets = selected
                                        this.onDismiss()
                                    },
                                )
                            }
                        },
                        content = { Text(stringResource(R.string.keyword_auto_reply_targets_title)) },
                        supportingContent = {
                            Text(
                                if (targets.isEmpty()) {
                                    stringResource(R.string.keyword_auto_reply_targets_empty)
                                } else {
                                    stringResource(R.string.keyword_auto_reply_targets_count, targets.size)
                                }
                            )
                        },
                    )

                    if (rules.isEmpty()) {
                        Text(
                            stringResource(R.string.keyword_auto_reply_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp),
                        ) {
                            items(rules, key = { it.id }) { rule ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        showAutoReplyRuleEditor(
                                            context = context,
                                            titleRes = R.string.keyword_auto_reply_edit_title,
                                            initial = rule,
                                            contacts = contacts,
                                        ) { edited ->
                                            val index = rules.indexOfFirst { it.id == edited.id }
                                            if (index >= 0) rules[index] = edited
                                        }
                                    },
                                    content = {
                                        Text(rule.keywords.joinToString(" / "))
                                    },
                                    supportingContent = { Text(ruleContentSummary(rule)) },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Switch(
                                                checked = rule.enabled,
                                                onCheckedChange = { enabled ->
                                                    val index = rules.indexOfFirst {
                                                        it.id == rule.id
                                                    }
                                                    if (index >= 0) {
                                                        rules[index] = rule.copy(enabled = enabled)
                                                    }
                                                },
                                            )
                                            IconButton(
                                                onClick = { rules.removeAll { it.id == rule.id } }
                                            ) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(
                                                        R.string.keyword_auto_reply_delete
                                                    ),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    KeywordAutoReply.rules = rules.toList()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** 列表里的回复预览: 文字直接展示, 媒体类显示类型名。 */
@Composable
private fun ruleContentSummary(rule: AutoReplyRule): String {
    val items = rule.effectiveItems()
    if (items.isEmpty()) return "-"
    return items.joinToString(" / ") { item ->
        if (item.type == ScheduledMessageContentType.TEXT) {
            item.value
        } else {
            contentItemTypeLabel(item.type)
        }
    }
}

private fun showAutoReplyRuleEditor(
    context: Context,
    @StringRes titleRes: Int,
    initial: AutoReplyRule,
    contacts: List<IWeContact>,
    onSave: (AutoReplyRule) -> Unit,
) {
    showComposeDialog(context) {
        val localizedContext = LocalXiaoFengLocalizedContext.current
        var keywordsText by remember { mutableStateOf(initial.keywords.joinToString("\n")) }
        val items = remember {
            initial.effectiveItems()
                .ifEmpty { listOf(ScheduledMessageContent()) }
                .toMutableStateList()
        }
        var matchExact by remember { mutableStateOf(initial.matchExact) }
        var enabled by remember { mutableStateOf(initial.enabled) }
        var ruleTargets by remember { mutableStateOf(initial.targets) }

        fun updateItem(index: Int, value: ScheduledMessageContent) {
            items[index] = value
        }

        fun removeItem(index: Int) {
            items.removeAt(index)
            if (items.isEmpty()) items.add(ScheduledMessageContent())
        }

        val keywords = keywordsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val hasContent = items.any { it.value.isNotBlank() }
        val canSave = keywords.isNotEmpty() && hasContent

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(titleRes)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DefaultColumn {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = keywordsText,
                            onValueChange = { keywordsText = it },
                            label = { Text(stringResource(R.string.keyword_auto_reply_keywords)) },
                            placeholder = {
                                Text(stringResource(R.string.keyword_auto_reply_keywords_hint))
                            },
                            minLines = 2,
                        )

                        Text(
                            stringResource(R.string.keyword_auto_reply_items_title),
                            style = MaterialTheme.typography.labelLarge,
                        )

                        items.forEachIndexed { index, item ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                DropDownMenuWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.scheduled_messages_item_type),
                                    description = null,
                                    value = item.type,
                                    options = ScheduledMessageContentType.entries.map {
                                        DropdownOption(it, contentItemTypeLabel(it))
                                    },
                                    onValueChange = { updateItem(index, item.copy(type = it)) },
                                )

                                if (item.usesTextField()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            modifier = Modifier.weight(1f),
                                            value = item.value,
                                            onValueChange = {
                                                updateItem(index, item.copy(value = it))
                                            },
                                            label = {
                                                Text(
                                                    if (item.type == ScheduledMessageContentType.TEXT) {
                                                        stringResource(R.string.scheduled_messages_content)
                                                    } else {
                                                        stringResource(R.string.scheduled_messages_item_xml)
                                                    }
                                                )
                                            },
                                            minLines = 2,
                                        )
                                        IconButton(onClick = { removeItem(index) }) {
                                            Icon(
                                                MaterialSymbols.Outlined.Delete,
                                                contentDescription = stringResource(
                                                    R.string.scheduled_messages_remove_item
                                                ),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                } else {
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            pickPanelFile(
                                                context,
                                                item.pickMimeTypes()
                                            ) { name, uri, activity ->
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    val path = importScheduledFile(activity, uri, name)
                                                    withContext(Dispatchers.Main) {
                                                        activity.finish()
                                                        if (path == null) {
                                                            showToast(
                                                                activity,
                                                                localizedContext.getString(
                                                                    R.string.scheduled_messages_import_failed
                                                                ),
                                                            )
                                                        } else {
                                                            updateItem(
                                                                index,
                                                                item.copy(value = path)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        content = {
                                            Text(stringResource(R.string.scheduled_messages_pick_file))
                                        },
                                        supportingContent = {
                                            Text(
                                                text = item.value.ifBlank { "-" },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        trailingContent = {
                                            IconButton(onClick = { removeItem(index) }) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(
                                                        R.string.scheduled_messages_remove_item
                                                    ),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        TextButton(onClick = { items.add(ScheduledMessageContent()) }) {
                            Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.keyword_auto_reply_add_item))
                        }

                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = stringResource(R.string.keyword_auto_reply_rule_targets_title),
                                        contacts = contacts,
                                        initialSelectedWxIds = ruleTargets,
                                        onDismiss = this.onDismiss,
                                        onConfirm = { selected ->
                                            ruleTargets = selected
                                            this.onDismiss()
                                        },
                                    )
                                }
                            },
                            content = {
                                Text(stringResource(R.string.keyword_auto_reply_rule_targets_title))
                            },
                            supportingContent = {
                                Text(
                                    if (ruleTargets.isEmpty()) {
                                        stringResource(R.string.keyword_auto_reply_rule_targets_follow_global)
                                    } else {
                                        stringResource(
                                            R.string.keyword_auto_reply_rule_targets_count,
                                            ruleTargets.size,
                                        )
                                    }
                                )
                            },
                        )

                        ListItem(
                            content = { Text(stringResource(R.string.keyword_auto_reply_exact)) },
                            supportingContent = {
                                Text(stringResource(R.string.keyword_auto_reply_exact_description))
                            },
                            trailingContent = {
                                Switch(checked = matchExact, onCheckedChange = { matchExact = it })
                            },
                        )

                        ListItem(
                            content = { Text(stringResource(R.string.keyword_auto_reply_enabled)) },
                            trailingContent = {
                                Switch(checked = enabled, onCheckedChange = { enabled = it })
                            },
                        )

                        if (!canSave) {
                            Text(
                                stringResource(
                                    if (keywords.isEmpty()) R.string.keyword_auto_reply_need_keywords
                                    else R.string.keyword_auto_reply_need_reply
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        onSave(
                            initial.copy(
                                keywords = keywords,
                                reply = "",
                                items = items.filter { it.value.isNotBlank() },
                                matchExact = matchExact,
                                enabled = enabled,
                                targets = ruleTargets,
                            )
                        )
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}
