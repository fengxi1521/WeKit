package com.xiao.feng.features.items.chat.scheduledmessages

import android.content.Context
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
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeContact
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
import com.xiao.feng.ui.content.parseDateTime
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
private fun targetLabel(targets: List<ScheduledMessageTarget>): String = when {
    targets.isEmpty() -> stringResource(R.string.scheduled_messages_select_target)
    targets.size <= 2 -> targets.joinToString(" ") { target ->
        target.displayName.takeIf { it.isNotBlank() } ?: target.talker
    }

    else -> stringResource(R.string.scheduled_messages_target_count, targets.size)
}

@Composable
private fun ScheduledMessage.summary(): String {
    val targets = targetLabel(this.targets)
    return when (kind) {
        ScheduledMessageKind.REPEATING -> stringResource(
            R.string.scheduled_messages_repeating_summary,
            daysLabel(daysOfWeek),
            minutesOfDay.sorted().joinToString(" ") { formatMinuteOfDay(it) },
            targets,
        )

        ScheduledMessageKind.ONCE -> stringResource(
            R.string.scheduled_messages_once_summary,
            formatDateTime(atEpochMillis),
            targets,
        )
    }
}

private fun newScheduledMessage(contacts: List<WeContact>): ScheduledMessage {
    val first = contacts.firstOrNull()
    return ScheduledMessage(
        id = newScheduledMessageId(),
        targets = first?.let {
            listOf(ScheduledMessageTarget(it.wxId, it.displayName))
        }.orEmpty(),
        text = "",
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
                                            initial = message,
                                            contacts = contacts,
                                        ) { edited ->
                                            val index = messages.indexOfFirst { it.id == edited.id }
                                            if (index >= 0) messages[index] = edited
                                        }
                                    },
                                    content = { Text(message.summary()) },
                                    supportingContent = {
                                        Text(
                                            message.targets.joinToString(" ") { target ->
                                                target.displayName.takeIf { it.isNotBlank() }
                                                    ?: target.talker
                                            }
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                onClick = {
                                                    val sent = ScheduledMessageSchedule.sendNow(message)
                                                    showToast(
                                                        context,
                                                        localizedContext.getString(
                                                            R.string.scheduled_messages_send_now_result,
                                                            sent,
                                                            message.targets.size,
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

        val onceMillis = parseDateTime(onceText)
        val isOncePast = draft.kind == ScheduledMessageKind.ONCE &&
                onceMillis != null && onceMillis <= System.currentTimeMillis()

        val candidate = when (draft.kind) {
            ScheduledMessageKind.REPEATING -> draft
            ScheduledMessageKind.ONCE -> onceMillis?.let { draft.copy(atEpochMillis = it) }
        }

        val textLines = draft.text.lines().count { it.isNotBlank() }

        val canSave = candidate != null &&
                candidate.targets.isNotEmpty() &&
                candidate.targets.all { it.talker.isNotBlank() } &&
                candidate.text.isNotBlank() &&
                when (draft.kind) {
                    ScheduledMessageKind.REPEATING ->
                        draft.daysOfWeek.isNotEmpty() && draft.minutesOfDay.isNotEmpty()

                    ScheduledMessageKind.ONCE -> !isOncePast
                }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(titleRes)) },
            text = {
                DefaultColumn {
                    ListItem(
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
                        supportingContent = { Text(targetLabel(draft.targets)) },
                    )

                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = draft.text,
                        onValueChange = { draft = draft.copy(text = it) },
                        label = { Text(stringResource(R.string.scheduled_messages_content)) },
                        placeholder = { Text(stringResource(R.string.scheduled_messages_content_hint)) },
                        minLines = 2,
                    )

                    if (textLines >= 2) {
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

                            if (isOncePast) {
                                Text(
                                    stringResource(R.string.contacts_schedule_past_time),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (draft.targets.isEmpty()) {
                        Text(
                            stringResource(R.string.scheduled_messages_need_target),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (draft.text.isBlank()) {
                        Text(
                            stringResource(R.string.scheduled_messages_need_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        if (candidate != null) {
                            onSave(candidate)
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
