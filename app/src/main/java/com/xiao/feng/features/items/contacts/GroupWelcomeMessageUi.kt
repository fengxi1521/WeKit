package com.xiao.feng.features.items.contacts

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.features.items.chat.LocalStickerFilesContent
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.m3.BaseSupportingWidget
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 进群欢迎语的全局配置: 开关、生效群聊、欢迎文案、随机表情包。
 *
 * 所有修改立即生效, 没有单独的保存按钮。
 */
fun showGroupWelcomeMessageDialog(context: Context) {
    showComposeDialog(context) {
        var enabled by remember { mutableStateOf(GroupWelcomeMessage.isEnabled) }
        var targets by remember { mutableStateOf(GroupWelcomeMessage.targets) }
        var template by remember { mutableStateOf(GroupWelcomeMessage.template) }
        var emojis by remember { mutableStateOf(GroupWelcomeMessage.emojiPaths) }
        var groups by remember { mutableStateOf<List<IWeContact>>(emptyList()) }

        LaunchedEffect(Unit) {
            groups = withContext(Dispatchers.IO) {
                if (!WeDatabaseApi.isReady) return@withContext emptyList<IWeContact>()
                runCatching { WeDatabaseApi.getGroups() }.getOrDefault(emptyList())
            }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.feature_group_welcome_message_name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.group_welcome_enabled),
                                description = stringResource(R.string.group_welcome_enabled_summary),
                                checked = enabled,
                                onCheckedChange = {
                                    enabled = it
                                    GroupWelcomeMessage.applyToggle(it)
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.group_welcome_targets_title),
                                description = if (targets.isEmpty()) {
                                    stringResource(R.string.group_welcome_targets_all)
                                } else {
                                    stringResource(
                                        R.string.group_welcome_targets_count,
                                        targets.size,
                                    )
                                },
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(R.string.group_welcome_select_title),
                                            contacts = groups,
                                            initialSelectedWxIds = targets,
                                            onDismiss = this.onDismiss,
                                            onConfirm = { selected ->
                                                targets = selected
                                                GroupWelcomeMessage.targets = selected
                                                this.onDismiss()
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.group_welcome_template),
                                description = stringResource(R.string.group_welcome_template_hint),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    value = template,
                                    onValueChange = {
                                        template = it
                                        GroupWelcomeMessage.template = it
                                    },
                                    placeholder = {
                                        Text(stringResource(R.string.group_welcome_template_hint))
                                    },
                                    minLines = 3,
                                )
                            }
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.group_welcome_emojis_title),
                                description = if (emojis.isEmpty()) {
                                    stringResource(R.string.emoji_files_none)
                                } else {
                                    stringResource(
                                        R.string.group_welcome_emojis_count,
                                        emojis.size,
                                    )
                                },
                                onClick = {
                                    showComposeDialog(context) {
                                        AlertDialogContent(
                                            modifier = Modifier.fillMaxWidth(),
                                            title = {
                                                Text(
                                                    stringResource(R.string.emoji_files_dialog_title),
                                                )
                                            },
                                            text = {
                                                LocalStickerFilesContent(context, emojis) { selected ->
                                                    emojis = selected
                                                    GroupWelcomeMessage.emojiPaths = selected
                                                }
                                            },
                                            confirmButton = {
                                                Button(onClick = onDismiss) {
                                                    Text(stringResource(R.string.action_done))
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    val preview = template
                        .replace("{member}", stringResource(R.string.group_welcome_preview_member))
                        .replace("{group}", stringResource(R.string.group_welcome_preview_group))
                        .trim()
                    if (preview.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.group_welcome_preview, preview),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
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
