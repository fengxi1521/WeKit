package com.xiao.feng.features.items.chat.ai

import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 「艾特回复」设置: 开关、生效群聊、回复延迟。所有修改立即生效。 */
fun showAtReplyDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var enabled by remember { mutableStateOf(AtReply.isEnabled) }
        var targets by remember { mutableStateOf(AtReply.targets) }
        var delayInput by remember { mutableIntStateOf(AtReply.replyDelaySeconds) }
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
