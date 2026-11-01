package com.xiao.feng.features.items.contacts

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun showGroupWelcomeMessageDialog(context: Context, groupId: String) {
    showComposeDialog(context) {
        var enabled by remember(groupId) { mutableStateOf(GroupWelcomeMessage.isEnabledForGroup(groupId)) }
        var template by remember(groupId) { mutableStateOf(GroupWelcomeMessage.templateForGroup(groupId)) }
        var groupName by remember(groupId) { mutableStateOf("") }

        LaunchedEffect(groupId) {
            groupName = withContext(Dispatchers.IO) { WeDatabaseApi.getDisplayName(groupId) }
        }

        val preview = template
            .replace("{member}", stringResource(R.string.group_welcome_preview_member))
            .replace("{group}", stringResource(R.string.group_welcome_preview_group))
            .trim()

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text(
                    if (groupName.isBlank()) {
                        stringResource(R.string.group_welcome_dialog_title)
                    } else {
                        stringResource(R.string.group_welcome_dialog_title_group, groupName)
                    }
                )
            },
            text = {
                DefaultColumn {
                    ListItem(
                        content = { Text(stringResource(R.string.group_welcome_enabled)) },
                        supportingContent = {
                            Text(stringResource(R.string.group_welcome_enabled_summary))
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { enabled = it },
                            )
                        },
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = template,
                        onValueChange = { template = it },
                        label = { Text(stringResource(R.string.group_welcome_template)) },
                        placeholder = { Text(stringResource(R.string.group_welcome_template_hint)) },
                        minLines = 3,
                    )

                    Text(
                        text = if (preview.isBlank()) {
                            stringResource(R.string.group_welcome_template_hint)
                        } else {
                            stringResource(R.string.group_welcome_preview, preview)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    GroupWelcomeMessage.saveGroupConfig(groupId, enabled, template)
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
