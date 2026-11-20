package com.xiao.feng.features.items.chat.ai

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 「艾特回复」设置: 开关、生效群聊、回复延迟。所有修改立即生效。 */
fun showAtReplyDialog(context: Context) {
    showComposeDialog(context) {
        var enabled by remember { mutableStateOf(AtReply.isEnabled) }
        var targets by remember { mutableStateOf(AtReply.targets) }
        var autoAddNewGroups by remember { mutableStateOf(AtReply.autoAddNewGroups) }
        var delayInput by remember { mutableIntStateOf(AtReply.replyDelaySeconds) }
        var contextInput by remember { mutableIntStateOf(AtReply.contextCount) }
        var personaInput by remember { mutableStateOf(AtReply.persona) }
        var groups by remember { mutableStateOf<List<IWeContact>>(emptyList()) }

        LaunchedEffect(Unit) {
            groups = withContext(Dispatchers.IO) {
                if (!WeDatabaseApi.isReady) return@withContext emptyList<IWeContact>()
                runCatching { WeDatabaseApi.getGroups() }.getOrDefault(emptyList())
            }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.feature_at_reply_name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.at_reply_switch_title),
                                description = stringResource(R.string.at_reply_switch_description),
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    AtReply.applyToggle(it)
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.at_reply_auto_add_title),
                                description = stringResource(R.string.at_reply_auto_add_description),
                                checked = autoAddNewGroups,
                                onCheckedChange = {
                                    autoAddNewGroups = it
                                    // 刚开启时先把现有群记为已知, 之后只有真正的新群才会被加入。
                                    if (it) AtReply.markGroupsKnown(groups.map { group -> group.wxId })
                                    AtReply.autoAddNewGroups = it
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.at_reply_targets_title),
                                description = if (targets.isEmpty()) {
                                    stringResource(R.string.at_reply_targets_all)
                                } else {
                                    stringResource(R.string.at_reply_targets_count, targets.size)
                                },
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(R.string.at_reply_select_title),
                                            contacts = groups,
                                            initialSelectedWxIds = targets,
                                            onDismiss = this.onDismiss,
                                            onConfirm = { selected ->
                                                targets = selected
                                                AtReply.targets = selected
                                                // 清空选择表示「全部群聊」, 此时旧的单群排除不再适用。
                                                if (selected.isEmpty()) AtReply.excludedTargets = emptySet()
                                                this.onDismiss()
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.at_reply_persona_title),
                                description = personaInput.ifBlank {
                                    stringResource(R.string.at_reply_persona_follow_ai)
                                },
                                onClick = {
                                    showComposeDialog(context) {
                                        var draft by remember { mutableStateOf(personaInput) }
                                        AlertDialogContent(
                                            title = { Text(stringResource(R.string.at_reply_persona_title)) },
                                            text = {
                                                OutlinedTextField(
                                                    value = draft,
                                                    onValueChange = { draft = it },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    minLines = 4,
                                                    supportingText = {
                                                        Text(stringResource(R.string.at_reply_persona_description))
                                                    },
                                                )
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    personaInput = draft
                                                    AtReply.persona = draft
                                                    onDismiss()
                                                }) { Text(stringResource(R.string.dialog_confirm)) }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = onDismiss) {
                                                    Text(stringResource(R.string.dialog_cancel))
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.at_reply_context_title),
                                    description = stringResource(R.string.at_reply_context_description),
                                    value = contextInput,
                                    startInt = 0,
                                    endInt = 50,
                                    stepSize = 1,
                                    valueSuffix = "",
                                    onValueChange = {
                                        contextInput = it
                                        AtReply.contextCount = it
                                    },
                                )
                            }
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.at_reply_delay_title),
                                    description = stringResource(R.string.at_reply_delay_description),
                                    value = delayInput,
                                    startInt = 0,
                                    endInt = 30,
                                    stepSize = 1,
                                    valueSuffix = "s",
                                    onValueChange = {
                                        delayInput = it
                                        AtReply.replyDelaySeconds = it
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}

/** 聊天菜单长按入口: 只调整当前群的艾特回复, 不进完整设置页。 */
fun showAtReplyGroupDialog(context: Context, talker: String) {
    if (!talker.isGroupChatWxId) {
        showAtReplyDialog(context)
        return
    }
    showComposeDialog(context) {
        var enabled by remember { mutableStateOf(AtReply.isEnabled) }
        var groupEnabled by remember { mutableStateOf(AtReply.isGroupEnabled(talker)) }
        AlertDialogContent(
            title = { Text(stringResource(R.string.at_reply_group_settings_title)) },
            text = {
                SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                    item {
                        SwitchWidget(
                            title = stringResource(R.string.at_reply_switch_title),
                            description = stringResource(R.string.at_reply_switch_description),
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                AtReply.applyToggle(it)
                            },
                        )
                    }
                    item {
                        SwitchWidget(
                            title = stringResource(R.string.at_reply_group_switch_title),
                            description = stringResource(R.string.at_reply_group_switch_description),
                            checked = groupEnabled,
                            onCheckedChange = {
                                groupEnabled = it
                                AtReply.setGroupEnabled(talker, it)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}
