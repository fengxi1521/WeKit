package com.xiao.feng.features.items.chat.ai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.WeTimeOfDayField
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun showAiChatDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var enabledInput by remember { mutableStateOf(AiChat.chatEnabled) }
        var targetsInput by remember { mutableStateOf(AiChat.targets) }
        var apiKeyInput by remember { mutableStateOf(AiChat.apiKey) }
        var baseUrlInput by remember { mutableStateOf(AiChat.baseUrl) }
        var modelInput by remember { mutableStateOf(AiChat.model) }
        var personaInput by remember { mutableStateOf(AiChat.persona) }
        var contextInput by remember { mutableIntStateOf(AiChat.contextCount) }
        var mentionInput by remember { mutableStateOf(AiChat.mentionEnabled) }
        var mentionPercentInput by remember { mutableIntStateOf(AiChat.mentionPercent) }
        var delayInput by remember { mutableIntStateOf(AiChat.replyDelaySeconds) }
        var quoteInput by remember { mutableStateOf(AiChat.quoteReply) }
        var stickerInput by remember { mutableStateOf(AiChat.stickerEnabled) }
        var stickerPercentInput by remember { mutableIntStateOf(AiChat.stickerPercent) }
        var stickerMd5sInput by remember { mutableStateOf(AiChat.stickerMd5s) }
        var bubbleInput by remember { mutableStateOf(AiChat.bubbleEnabled) }
        var bubbleStartInput by remember { mutableIntStateOf(AiChat.bubbleStartMinute) }
        var bubbleEndInput by remember { mutableIntStateOf(AiChat.bubbleEndMinute) }
        var bubbleMinInput by remember { mutableIntStateOf(AiChat.bubbleMinMinutes) }
        var bubbleMaxInput by remember { mutableIntStateOf(AiChat.bubbleMaxMinutes) }

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
            title = { Text(stringResource(R.string.feature_ai_chat_name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.ai_chat_enabled_title),
                                description = stringResource(R.string.ai_chat_enabled_description),
                                checked = enabledInput,
                                onCheckedChange = {
                                    enabledInput = it
                                    AiChat.chatEnabled = it
                                    AiChat.restartAutomation()
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.ai_chat_targets_title),
                                description = if (targetsInput.isEmpty()) {
                                    stringResource(R.string.ai_chat_targets_empty)
                                } else {
                                    stringResource(R.string.ai_chat_targets_count, targetsInput.size)
                                },
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(R.string.ai_chat_select_title),
                                            contacts = contacts,
                                            initialSelectedWxIds = targetsInput,
                                            onDismiss = this.onDismiss,
                                            onConfirm = { selected ->
                                                targetsInput = selected
                                                AiChat.targets = selected
                                                AiChat.restartAutomation()
                                                this.onDismiss()
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (enabledInput) {
                        SegmentedColumn(
                            title = stringResource(R.string.ai_chat_section_model),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_chat_api_key_title),
                                    value = apiKeyInput,
                                    onValueChange = {
                                        apiKeyInput = it
                                        AiChat.apiKey = it
                                    },
                                    dialogTitle = stringResource(R.string.ai_chat_api_key_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.ai_chat_api_key_hint),
                                    password = true,
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_chat_base_url_title),
                                    value = baseUrlInput,
                                    onValueChange = {
                                        baseUrlInput = it
                                        AiChat.baseUrl = it
                                    },
                                    dialogTitle = stringResource(R.string.ai_chat_base_url_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.ai_chat_base_url_hint),
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_chat_model_title),
                                    value = modelInput,
                                    onValueChange = {
                                        modelInput = it
                                        AiChat.model = it
                                    },
                                    dialogTitle = stringResource(R.string.ai_chat_model_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.ai_chat_model_hint),
                                )
                            }
                            item {
                                TextFieldDialogWidget(
                                    title = stringResource(R.string.ai_chat_persona_title),
                                    value = personaInput,
                                    onValueChange = {
                                        personaInput = it
                                        AiChat.persona = it
                                    },
                                    dialogTitle = stringResource(R.string.ai_chat_persona_title),
                                    confirmLabel = stringResource(R.string.dialog_confirm),
                                    dismissLabel = stringResource(R.string.dialog_cancel),
                                    valueHint = stringResource(R.string.ai_chat_persona_hint),
                                    singleLine = false,
                                )
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.ai_chat_section_reply),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.ai_chat_context_title),
                                        description = stringResource(R.string.ai_chat_context_description),
                                        value = contextInput,
                                        startInt = 0,
                                        endInt = 50,
                                        stepSize = 1,
                                        valueSuffix = "",
                                        onValueChange = {
                                            contextInput = it
                                            AiChat.contextCount = it
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.ai_chat_reply_delay_title),
                                        value = delayInput,
                                        startInt = 0,
                                        endInt = 60,
                                        stepSize = 1,
                                        valueSuffix = "s",
                                        onValueChange = {
                                            delayInput = it
                                            AiChat.replyDelaySeconds = it
                                        },
                                    )
                                }
                            }
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.ai_chat_mention_title),
                                    description = stringResource(R.string.ai_chat_mention_description),
                                    checked = mentionInput,
                                    onCheckedChange = {
                                        mentionInput = it
                                        AiChat.mentionEnabled = it
                                    },
                                )
                            }
                            if (mentionInput) {
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.ai_chat_mention_percent_title),
                                            value = mentionPercentInput,
                                            startInt = 0,
                                            endInt = 100,
                                            stepSize = 5,
                                            valueSuffix = "%",
                                            onValueChange = {
                                                mentionPercentInput = it
                                                AiChat.mentionPercent = it
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.ai_chat_section_humanize),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.ai_chat_quote_title),
                                    description = stringResource(R.string.ai_chat_quote_description),
                                    checked = quoteInput,
                                    onCheckedChange = {
                                        quoteInput = it
                                        AiChat.quoteReply = it
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.ai_chat_sticker_title),
                                    description = stringResource(R.string.ai_chat_sticker_description),
                                    checked = stickerInput,
                                    onCheckedChange = {
                                        stickerInput = it
                                        AiChat.stickerEnabled = it
                                    },
                                )
                            }
                            if (stickerInput) {
                                item {
                                    BaseWidget(
                                        title = stringResource(R.string.ai_chat_sticker_select_title),
                                        description = if (stickerMd5sInput.isEmpty()) {
                                            stringResource(R.string.ai_chat_sticker_select_empty)
                                        } else {
                                            stringResource(
                                                R.string.ai_chat_sticker_select_count,
                                                stickerMd5sInput.size,
                                            )
                                        },
                                        onClick = {
                                            showStickerPickerDialog(context, stickerMd5sInput) { selected ->
                                                stickerMd5sInput = selected
                                                AiChat.stickerMd5s = selected
                                            }
                                        },
                                    )
                                }
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.ai_chat_sticker_percent_title),
                                            description = stringResource(R.string.ai_chat_sticker_percent_description),
                                            value = stickerPercentInput,
                                            startInt = 0,
                                            endInt = 100,
                                            stepSize = 5,
                                            valueSuffix = "%",
                                            onValueChange = {
                                                stickerPercentInput = it
                                                AiChat.stickerPercent = it
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.ai_chat_section_bubble),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.ai_chat_bubble_title),
                                    description = stringResource(R.string.ai_chat_bubble_description),
                                    checked = bubbleInput,
                                    onCheckedChange = {
                                        bubbleInput = it
                                        AiChat.bubbleEnabled = it
                                        AiChat.restartAutomation()
                                    },
                                )
                            }
                            if (bubbleInput) {
                                item {
                                    WeTimeOfDayField(
                                        minuteOfDay = bubbleStartInput,
                                        label = stringResource(R.string.ai_chat_bubble_start_title),
                                        onMinuteChange = {
                                            bubbleStartInput = it
                                            AiChat.bubbleStartMinute = it
                                            AiChat.restartAutomation()
                                        },
                                    )
                                }
                                item {
                                    WeTimeOfDayField(
                                        minuteOfDay = bubbleEndInput,
                                        label = stringResource(R.string.ai_chat_bubble_end_title),
                                        onMinuteChange = {
                                            bubbleEndInput = it
                                            AiChat.bubbleEndMinute = it
                                            AiChat.restartAutomation()
                                        },
                                    )
                                }
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.ai_chat_bubble_min_title),
                                            value = bubbleMinInput,
                                            startInt = 1,
                                            endInt = 720,
                                            stepSize = 1,
                                            valueSuffix = "min",
                                            onValueChange = {
                                                bubbleMinInput = it
                                                AiChat.bubbleMinMinutes = it
                                                AiChat.restartAutomation()
                                            },
                                        )
                                    }
                                }
                                item {
                                    BaseItemContainer {
                                        IntNumberPickerWidget(
                                            title = stringResource(R.string.ai_chat_bubble_max_title),
                                            value = bubbleMaxInput,
                                            startInt = 1,
                                            endInt = 1440,
                                            stepSize = 1,
                                            valueSuffix = "min",
                                            onValueChange = {
                                                bubbleMaxInput = it
                                                AiChat.bubbleMaxMinutes = it
                                                AiChat.restartAutomation()
                                            },
                                        )
                                    }
                                }
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

/** 让用户从自己的微信自定义表情里挑出 AI 允许发送的那些。 */
private fun showStickerPickerDialog(
    context: ComponentActivity,
    initial: Set<String>,
    onUpdated: (Set<String>) -> Unit,
) {
    showComposeDialog(context) {
        var allStickers by remember { mutableStateOf<List<String>?>(null) }
        var selected by remember { mutableStateOf(initial) }

        LaunchedEffect(Unit) {
            allStickers = withContext(Dispatchers.IO) { AiChatStickers.listMd5s() }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.ai_chat_sticker_dialog_title)) },
            text = {
                when (val stickers = allStickers) {
                    null -> Text(stringResource(R.string.ai_chat_sticker_loading))
                    else -> if (stickers.isEmpty()) {
                        Text(stringResource(R.string.ai_chat_sticker_empty))
                    } else {
                        LazyColumn {
                            itemsIndexed(stickers, key = { _, md5 -> md5 }) { index, md5 ->
                                StickerSelectRow(
                                    md5 = md5,
                                    index = index,
                                    checked = md5 in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + md5 else selected - md5
                                        onUpdated(selected)
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

@Composable
private fun StickerSelectRow(
    md5: String,
    index: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    var preview by remember(md5) { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(md5) {
        preview = withContext(Dispatchers.IO) {
            val destination = AiChatStickers.stickerFile(md5)
            val cached = destination.toFile()
            if (cached.isFile) cached
            else WeMessageApi.decodeStickerToFile(md5, destination)?.toFile()
        }
    }

    BaseItemContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                preview?.let {
                    AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(44.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.ai_chat_sticker_item, index + 1))
                Text(md5.take(8), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
