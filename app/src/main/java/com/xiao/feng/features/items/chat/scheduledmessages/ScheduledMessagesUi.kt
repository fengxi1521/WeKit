package com.xiao.feng.features.items.chat.scheduledmessages

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.features.items.chat.panel.pickPanelFile
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.IconButton
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.WeDateTimeField
import com.xiao.feng.ui.content.WeDateTimeMode
import com.xiao.feng.ui.content.WeTimeOfDayField
import com.xiao.feng.ui.content.currentMinuteOfDay
import com.xiao.feng.ui.content.formatDateTime
import com.xiao.feng.ui.content.formatMinuteOfDay
import com.xiao.feng.ui.content.m3.DropDownMenuWidget
import com.xiao.feng.ui.content.m3.DropdownOption
import com.xiao.feng.ui.content.parseDateTime
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private val DAY_LABELS: List<Pair<Int, Int>> = listOf(
    Calendar.MONDAY to R.string.contacts_schedule_monday,
    Calendar.TUESDAY to R.string.contacts_schedule_tuesday,
    Calendar.WEDNESDAY to R.string.contacts_schedule_wednesday,
    Calendar.THURSDAY to R.string.contacts_schedule_thursday,
    Calendar.FRIDAY to R.string.contacts_schedule_friday,
    Calendar.SATURDAY to R.string.contacts_schedule_saturday,
    Calendar.SUNDAY to R.string.contacts_schedule_sunday,
)

private const val DEFAULT_ONCE_OFFSET_MILLIS = 60L * 60L * 1000L

@Composable
private fun daysLabel(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.contacts_schedule_never)
    days.containsAll(ALL_SCHEDULED_MESSAGE_DAYS) -> stringResource(R.string.contacts_schedule_every_day)
    else -> {
        val labels = mutableListOf<String>()
        for ((day, labelRes) in DAY_LABELS) {
            if (day in days) labels += stringResource(labelRes)
        }
        labels.joinToString(" ")
    }
}

@Composable
private fun targetLabel(message: ScheduledMessage): String = when (message.targetType) {
    ScheduledMessageTargetType.MOMENTS -> stringResource(R.string.scheduled_messages_target_moments)
    ScheduledMessageTargetType.CHAT -> when {
        message.targets.isEmpty() -> stringResource(R.string.scheduled_messages_select_target)
        message.targets.size <= 2 -> message.targets.joinToString(" ") { target ->
            target.displayName.takeIf { it.isNotBlank() } ?: target.talker
        }

        else -> stringResource(R.string.scheduled_messages_target_count, message.targets.size)
    }
}

@Composable
private fun ScheduledMessage.contentPreview(): String {
    val content = normalizedItems()
    return when (targetType) {
        ScheduledMessageTargetType.MOMENTS -> {
            val images = content.count { it.type == ScheduledMessageContentType.IMAGE }
            val video = content.count { it.type == ScheduledMessageContentType.VIDEO }
            when {
                video > 0 -> stringResource(R.string.scheduled_messages_moments_video)
                images > 0 -> stringResource(R.string.scheduled_messages_moments_images, images)
                else -> content.firstOrNull()?.value.orEmpty()
            }
        }

        ScheduledMessageTargetType.CHAT -> content.firstOrNull()?.value
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }
}

@Composable
private fun ScheduledMessage.scheduleLabel(): String = when (kind) {
    ScheduledMessageKind.REPEATING -> stringResource(
        R.string.scheduled_messages_repeating_summary,
        daysLabel(daysOfWeek),
        minutesOfDay.sorted().joinToString(" ") { formatMinuteOfDay(it) },
        targetLabel(this),
    )

    ScheduledMessageKind.ONCE -> stringResource(
        R.string.scheduled_messages_once_summary,
        formatDateTime(atEpochMillis),
        targetLabel(this),
    )
}

private fun newScheduledMessage(contacts: List<WeContact>): ScheduledMessage {
    val first = contacts.firstOrNull()
    return ScheduledMessage(
        id = newScheduledMessageId(),
        targets = first?.let {
            listOf(ScheduledMessageTarget(it.wxId, it.displayName))
        }.orEmpty(),
        items = listOf(ScheduledMessageContent()),
        kind = ScheduledMessageKind.REPEATING,
        minutesOfDay = setOf(currentMinuteOfDay()),
        daysOfWeek = ALL_SCHEDULED_MESSAGE_DAYS,
        atEpochMillis = System.currentTimeMillis() + DEFAULT_ONCE_OFFSET_MILLIS,
    )
}

fun showScheduledMessagesDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        val localizedContext = LocalXiaoFengLocalizedContext.current
        val messages = remember { ScheduledMessageSchedule.messages.toMutableStateList() }
        val addedIds = remember { mutableSetOf<String>() }

        // Loading the contact list reads WeChat's database; keeping it off the main thread avoids a
        // visible stall when the dialog opens on a large account.
        var contacts by remember { mutableStateOf<List<WeContact>>(emptyList()) }
        var contactsLoaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            contacts = withContext(Dispatchers.IO) { WeDatabaseApi.getContacts() }
            contactsLoaded = true
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.scheduled_messages_title)) },
            text = {
                DefaultColumn {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.scheduled_messages_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = contactsLoaded,
                            onClick = {
                                showScheduledMessageEditor(
                                    context = context,
                                    titleRes = R.string.scheduled_messages_add_title,
                                    initial = newScheduledMessage(contacts),
                                    contacts = contacts,
                                ) { added ->
                                    messages.add(added)
                                    addedIds += added.id
                                }
                            }
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.scheduled_messages_add))
                        }
                    }

                    if (messages.isEmpty()) {
                        Text(
                            stringResource(R.string.scheduled_messages_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(messages, key = { it.id }) { message ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        showScheduledMessageEditor(
                                            context = context,
                                            titleRes = R.string.scheduled_messages_edit_title,
                                            initial = message.copy(
                                                items = message.normalizedItems(),
                                                text = "",
                                            ),
                                            contacts = contacts,
                                        ) { edited ->
                                            val index = messages.indexOfFirst { it.id == edited.id }
                                            if (index >= 0) messages[index] = edited
                                        }
                                    },
                                    content = {
                                        Text(
                                            text = message.remark.ifBlank {
                                                message.contentPreview()
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = message.scheduleLabel(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                onClick = {
                                                    val sent = ScheduledMessageSchedule.sendNow(message)
                                                    val total =
                                                        if (message.targetType ==
                                                            ScheduledMessageTargetType.MOMENTS
                                                        ) 1 else message.targets.size
                                                    showToast(
                                                        context,
                                                        localizedContext.getString(
                                                            R.string.scheduled_messages_send_now_result,
                                                            sent,
                                                            total,
                                                        ),
                                                    )
                                                }
                                            ) {
                                                Text(stringResource(R.string.scheduled_messages_send_now))
                                            }
                                            Switch(
                                                checked = message.enabled,
                                                onCheckedChange = { enabled ->
                                                    val index =
                                                        messages.indexOfFirst { it.id == message.id }
                                                    if (index >= 0) {
                                                        messages[index] =
                                                            message.copy(enabled = enabled)
                                                    }
                                                },
                                            )
                                            IconButton(
                                                onClick = {
                                                    messages.removeAll { it.id == message.id }
                                                }
                                            ) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.contacts_schedule_delete),
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
                    val draft = messages.toList()
                    val added = addedIds.toSet()
                    ScheduledMessageSchedule.mutate { stored ->
                        val storedIds = stored.mapTo(mutableSetOf()) { it.id }
                        draft.filter { it.id in storedIds || it.id in added }
                    }
                    onDismiss()
                }) {
                    Text(stringResource(R.string.dialog_confirm))
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

@Composable
private fun contentItemTypeLabel(type: ScheduledMessageContentType): String = stringResource(
    when (type) {
        ScheduledMessageContentType.TEXT -> R.string.scheduled_messages_item_text
        ScheduledMessageContentType.XML -> R.string.scheduled_messages_item_xml
        ScheduledMessageContentType.IMAGE -> R.string.scheduled_messages_item_image
        ScheduledMessageContentType.VIDEO -> R.string.scheduled_messages_item_video
        ScheduledMessageContentType.FILE -> R.string.scheduled_messages_item_file
        ScheduledMessageContentType.EMOJI -> R.string.scheduled_messages_item_emoji
        ScheduledMessageContentType.VOICE -> R.string.scheduled_messages_item_voice
    }
)

private fun ScheduledMessageContent.usesTextField(): Boolean =
    type == ScheduledMessageContentType.TEXT || type == ScheduledMessageContentType.XML

private fun ScheduledMessageContent.pickMimeTypes(): Array<String> = when (type) {
    ScheduledMessageContentType.IMAGE, ScheduledMessageContentType.EMOJI -> arrayOf("image/*")
    ScheduledMessageContentType.VIDEO -> arrayOf("video/*")
    ScheduledMessageContentType.VOICE -> arrayOf("audio/*")
    else -> arrayOf("*/*")
}

/** Copies a picked document into the module's files dir so the alarm can still read it later. */
private fun importScheduledFile(activity: FragmentActivity, uri: Uri, name: String): String? =
    runCatching {
        val dir = File(HostInfo.application.filesDir, "scheduled_messages").apply { mkdirs() }
        val safeName = name.substringAfterLast('/').ifBlank { "imported_file" }
        val dest = File(dir, "${System.currentTimeMillis()}_$safeName")
        val input = activity.contentResolver.openInputStream(uri)
            ?: error("cannot open picked file: $uri")
        input.use { stream ->
            dest.outputStream().use { output -> stream.copyTo(output) }
        }
        dest.absolutePath
    }.getOrNull()

private fun showScheduledMessageEditor(
    context: Context,
    @StringRes titleRes: Int,
    initial: ScheduledMessage,
    contacts: List<WeContact>,
    onSave: (ScheduledMessage) -> Unit,
) {
    showComposeDialog(context) {
        val localizedContext = LocalXiaoFengLocalizedContext.current
        var draft by remember { mutableStateOf(initial) }
        var onceText by remember {
            mutableStateOf(
                formatDateTime(
                    initial.atEpochMillis.takeIf { it > 0L }
                        ?: (System.currentTimeMillis() + DEFAULT_ONCE_OFFSET_MILLIS)
                )
            )
        }

        fun updateItem(index: Int, value: ScheduledMessageContent) {
            draft = draft.copy(
                items = draft.items.toMutableList().also { it[index] = value }
            )
        }

        val onceMillis = parseDateTime(onceText)
        val isOncePast = draft.kind == ScheduledMessageKind.ONCE &&
                onceMillis != null && onceMillis <= System.currentTimeMillis()

        val candidate = when (draft.kind) {
            ScheduledMessageKind.REPEATING -> draft
            ScheduledMessageKind.ONCE -> onceMillis?.let { draft.copy(atEpochMillis = it) }
        }

        val content = draft.normalizedItems()
        val contentValid = when (draft.targetType) {
            ScheduledMessageTargetType.CHAT -> content.isNotEmpty()
            ScheduledMessageTargetType.MOMENTS -> draft.isValidMoments()
        }
        val targetValid = when (draft.targetType) {
            ScheduledMessageTargetType.CHAT ->
                draft.targets.isNotEmpty() && draft.targets.all { it.talker.isNotBlank() }

            ScheduledMessageTargetType.MOMENTS -> true
        }
        val timeValid = when (draft.kind) {
            ScheduledMessageKind.REPEATING ->
                draft.daysOfWeek.isNotEmpty() && draft.minutesOfDay.isNotEmpty()

            ScheduledMessageKind.ONCE -> !isOncePast
        }
        val canSave = candidate != null && contentValid && targetValid && timeValid
        val hasMultiLineText = content.any {
            it.type == ScheduledMessageContentType.TEXT && it.value.lines().count { line ->
                line.isNotBlank()
            } >= 2
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(titleRes)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DefaultColumn {
                        OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = draft.remark,
                        onValueChange = { draft = draft.copy(remark = it) },
                        label = { Text(stringResource(R.string.scheduled_messages_remark)) },
                        placeholder = {
                            Text(stringResource(R.string.scheduled_messages_remark_hint))
                        },
                        singleLine = true,
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ScheduledMessageTargetType.entries.forEachIndexed { index, targetType ->
                            SegmentedButton(
                                selected = draft.targetType == targetType,
                                onClick = { draft = draft.copy(targetType = targetType) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index, ScheduledMessageTargetType.entries.size
                                ),
                            ) {
                                Text(
                                    stringResource(
                                        if (targetType == ScheduledMessageTargetType.CHAT) {
                                            R.string.scheduled_messages_target_chat
                                        } else {
                                            R.string.scheduled_messages_target_moments
                                        }
                                    )
                                )
                            }
                        }
                    }

                    when (draft.targetType) {
                        ScheduledMessageTargetType.CHAT -> ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = localizedContext.getString(R.string.scheduled_messages_select_target),
                                        contacts = contacts,
                                        initialSelectedWxIds = draft.targets.map { it.talker }.toSet(),
                                        onDismiss = this.onDismiss,
                                        onConfirm = { wxIds ->
                                            draft = draft.copy(
                                                targets = wxIds.map { wxId ->
                                                    ScheduledMessageTarget(
                                                        talker = wxId,
                                                        displayName = contacts
                                                            .firstOrNull { it.wxId == wxId }
                                                            ?.displayName
                                                            .orEmpty(),
                                                    )
                                                }
                                            )
                                            this.onDismiss()
                                        },
                                    )
                                }
                            },
                            content = { Text(stringResource(R.string.scheduled_messages_target)) },
                            supportingContent = { Text(targetLabel(draft)) },
                        )

                        ScheduledMessageTargetType.MOMENTS -> Text(
                            stringResource(R.string.scheduled_messages_moments_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        stringResource(R.string.scheduled_messages_items),
                        style = MaterialTheme.typography.labelLarge,
                    )

                    val typeOptions = ScheduledMessageContentType.entries.map {
                        DropdownOption(it, contentItemTypeLabel(it))
                    }

                    draft.items.forEachIndexed { index, item ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DropDownMenuWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.scheduled_messages_item_type),
                                description = null,
                                value = item.type,
                                options = typeOptions,
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
                                    IconButton(
                                        onClick = {
                                            draft = draft.copy(
                                                items = draft.items.filterIndexed { i, _ ->
                                                    i != index
                                                }
                                            )
                                        }
                                    ) {
                                        Icon(
                                            MaterialSymbols.Outlined.Delete,
                                            contentDescription = stringResource(R.string.scheduled_messages_remove_item),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            } else {
                                ListItem(
                                    modifier = Modifier.clickable {
                                        pickPanelFile(context, item.pickMimeTypes()) { name, uri, activity ->
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
                                                        updateItem(index, item.copy(value = path))
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
                                        IconButton(
                                            onClick = {
                                                draft = draft.copy(
                                                    items = draft.items.filterIndexed { i, _ ->
                                                        i != index
                                                    }
                                                )
                                            }
                                        ) {
                                            Icon(
                                                MaterialSymbols.Outlined.Delete,
                                                contentDescription = stringResource(R.string.scheduled_messages_remove_item),
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            draft = draft.copy(items = draft.items + ScheduledMessageContent())
                        }
                    ) {
                        Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                        Text(stringResource(R.string.scheduled_messages_add_item))
                    }

                    if (hasMultiLineText) {
                        ListItem(
                            content = { Text(stringResource(R.string.scheduled_messages_randomize)) },
                            supportingContent = {
                                Text(stringResource(R.string.scheduled_messages_randomize_description))
                            },
                            trailingContent = {
                                Switch(
                                    checked = draft.randomizeLines,
                                    onCheckedChange = { draft = draft.copy(randomizeLines = it) },
                                )
                            },
                        )
                    }

                    if (draft.items.size > 1) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = draft.itemIntervalSeconds.toString(),
                            onValueChange = { input ->
                                draft = draft.copy(
                                    itemIntervalSeconds = input.filter(Char::isDigit)
                                        .take(4).toIntOrNull() ?: 0
                                )
                            },
                            label = {
                                Text(stringResource(R.string.scheduled_messages_item_interval))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }

                    if (draft.targetType == ScheduledMessageTargetType.CHAT &&
                        draft.targets.size > 1
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = draft.intervalSeconds.toString(),
                                onValueChange = { input ->
                                    draft = draft.copy(
                                        intervalSeconds = input.filter(Char::isDigit)
                                            .take(4).toIntOrNull() ?: 0
                                    )
                                },
                                label = {
                                    Text(stringResource(R.string.scheduled_messages_interval))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                modifier = Modifier.weight(1f),
                                value = draft.intervalRandomSeconds.toString(),
                                onValueChange = { input ->
                                    draft = draft.copy(
                                        intervalRandomSeconds = input.filter(Char::isDigit)
                                            .take(4).toIntOrNull() ?: 0
                                    )
                                },
                                label = {
                                    Text(stringResource(R.string.scheduled_messages_interval_random))
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                            )
                        }
                    }

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ScheduledMessageKind.entries.forEachIndexed { index, kind ->
                            SegmentedButton(
                                selected = draft.kind == kind,
                                onClick = { draft = draft.copy(kind = kind) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index, ScheduledMessageKind.entries.size
                                ),
                            ) {
                                Text(
                                    stringResource(
                                        if (kind == ScheduledMessageKind.REPEATING)
                                            R.string.contacts_schedule_kind_repeating
                                        else R.string.contacts_schedule_kind_once
                                    )
                                )
                            }
                        }
                    }

                    when (draft.kind) {
                        ScheduledMessageKind.REPEATING -> {
                            Text(
                                stringResource(R.string.scheduled_messages_times),
                                style = MaterialTheme.typography.labelLarge,
                            )

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                draft.minutesOfDay.sorted().forEach { minute ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {
                                            draft = draft.copy(
                                                minutesOfDay = draft.minutesOfDay - minute
                                            )
                                        },
                                        label = { Text(formatMinuteOfDay(minute)) },
                                    )
                                }
                            }

                            TextButton(
                                onClick = {
                                    showComposeDialog(context) {
                                        var picked by remember { mutableStateOf(currentMinuteOfDay()) }
                                        AlertDialogContent(
                                            modifier = Modifier.fillMaxWidth(),
                                            title = {
                                                Text(
                                                    stringResource(
                                                        R.string.scheduled_messages_time_picker_title
                                                    )
                                                )
                                            },
                                            text = {
                                                DefaultColumn {
                                                    WeTimeOfDayField(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        label = stringResource(R.string.contacts_schedule_time),
                                                        minuteOfDay = picked,
                                                        onMinuteChange = { picked = it },
                                                    )
                                                }
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    draft = draft.copy(
                                                        minutesOfDay = draft.minutesOfDay + picked
                                                    )
                                                    this.onDismiss()
                                                }) {
                                                    Text(stringResource(R.string.dialog_confirm))
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = this.onDismiss) {
                                                    Text(stringResource(R.string.dialog_cancel))
                                                }
                                            },
                                        )
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.scheduled_messages_add_time))
                            }

                            if (draft.minutesOfDay.isEmpty()) {
                                Text(
                                    stringResource(R.string.scheduled_messages_need_time),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DAY_LABELS.forEach { (day, labelRes) ->
                                    val selected = day in draft.daysOfWeek
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val days = draft.daysOfWeek.toMutableSet()
                                            if (selected) days -= day else days += day
                                            draft = draft.copy(daysOfWeek = days)
                                        },
                                        label = { Text(stringResource(labelRes)) },
                                    )
                                }
                            }

                            if (draft.daysOfWeek.isEmpty()) {
                                Text(
                                    stringResource(R.string.contacts_schedule_select_day),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        ScheduledMessageKind.ONCE -> {
                            WeDateTimeField(
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.contacts_schedule_date_time),
                                value = onceText,
                                onValueChange = { onceText = it },
                                mode = WeDateTimeMode.DATE_TIME,
                            )
                            Text(
                                stringResource(R.string.scheduled_messages_once_deleted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            ListItem(
                                content = {
                                    Text(stringResource(R.string.scheduled_messages_send_on_timeout))
                                },
                                supportingContent = {
                                    Text(
                                        stringResource(
                                            R.string.scheduled_messages_send_on_timeout_desc
                                        )
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = draft.sendOnTimeout,
                                        onCheckedChange = {
                                            draft = draft.copy(sendOnTimeout = it)
                                        },
                                    )
                                },
                            )

                            if (isOncePast) {
                                Text(
                                    stringResource(R.string.contacts_schedule_past_time),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (draft.targetType == ScheduledMessageTargetType.CHAT &&
                        draft.targets.isEmpty()
                    ) {
                        Text(
                            stringResource(R.string.scheduled_messages_need_target),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!contentValid) {
                        Text(
                            stringResource(
                                if (draft.targetType == ScheduledMessageTargetType.CHAT) {
                                    R.string.scheduled_messages_need_items
                                } else {
                                    R.string.scheduled_messages_moments_invalid
                                }
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
                        if (candidate != null) {
                            onSave(
                                candidate.copy(
                                    remark = candidate.remark.trim(),
                                    text = "",
                                    items = content,
                                )
                            )
                            onDismiss()
                        }
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}
