package com.xiao.feng.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun showQqMusicOrderDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var targetsInput by remember { mutableStateOf(QqMusicOrder.targets) }
        var allowSelfInput by remember { mutableStateOf(QqMusicOrder.allowSelfCommand) }
        var triggersInput by remember { mutableStateOf(QqMusicOrder.triggersRaw) }
        var customSingerInput by remember { mutableStateOf(QqMusicOrder.customSingerEnabled) }
        var defaultSingerInput by remember { mutableStateOf(QqMusicOrder.defaultSinger) }
        var replaceSingerInput by remember { mutableStateOf(QqMusicOrder.replaceSingerWithNickname) }
        var replaceCoverInput by remember { mutableStateOf(QqMusicOrder.replaceCoverWithAvatar) }

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
            title = { Text(stringResource(R.string.feature_qq_music_order_name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseWidget(
                                title = stringResource(R.string.qq_music_targets_title),
                                description = if (targetsInput.isEmpty()) {
                                    stringResource(R.string.qq_music_targets_empty)
                                } else {
                                    stringResource(R.string.qq_music_targets_count, targetsInput.size)
                                },
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(R.string.qq_music_select_title),
                                            contacts = contacts,
                                            initialSelectedWxIds = targetsInput,
                                            onDismiss = this.onDismiss,
                                            onConfirm = { selected ->
                                                targetsInput = selected
                                                QqMusicOrder.targets = selected
                                                this.onDismiss()
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.qq_music_allow_self_title),
                                description = stringResource(R.string.qq_music_allow_self_description),
                                checked = allowSelfInput,
                                onCheckedChange = {
                                    allowSelfInput = it
                                    QqMusicOrder.allowSelfCommand = it
                                },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.qq_music_triggers_title),
                                value = triggersInput,
                                onValueChange = {
                                    triggersInput = it
                                    QqMusicOrder.triggersRaw = it
                                },
                                dialogTitle = stringResource(R.string.qq_music_triggers_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.qq_music_triggers_hint),
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.qq_music_custom_singer_title),
                                description = stringResource(R.string.qq_music_custom_singer_description),
                                checked = customSingerInput,
                                onCheckedChange = {
                                    customSingerInput = it
                                    QqMusicOrder.customSingerEnabled = it
                                },
                            )
                        }
                        if (customSingerInput) {
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.qq_music_default_singer_title),
                                    value = defaultSingerInput,
                                    onValueChange = {
                                        defaultSingerInput = it
                                        QqMusicOrder.defaultSinger = it
                                    },
                                    dialogTitle = stringResource(R.string.qq_music_default_singer_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.qq_music_default_singer_hint),
                                )
                            }
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.qq_music_replace_singer_title),
                                description = stringResource(R.string.qq_music_replace_singer_description),
                                checked = replaceSingerInput,
                                onCheckedChange = {
                                    replaceSingerInput = it
                                    QqMusicOrder.replaceSingerWithNickname = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.qq_music_replace_cover_title),
                                description = stringResource(R.string.qq_music_replace_cover_description),
                                checked = replaceCoverInput,
                                onCheckedChange = {
                                    replaceCoverInput = it
                                    QqMusicOrder.replaceCoverWithAvatar = it
                                },
                            )
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
