package com.xiao.feng.features.items.moments

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.IconButton
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.WeTimeOfDayField
import com.xiao.feng.ui.content.currentMinuteOfDay
import com.xiao.feng.ui.content.formatMinuteOfDay
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.showToast
import java.util.Calendar

private val MOMENT_DAY_LABELS: List<Pair<Int, Int>> = listOf(
    Calendar.MONDAY to R.string.contacts_schedule_monday,
    Calendar.TUESDAY to R.string.contacts_schedule_tuesday,
    Calendar.WEDNESDAY to R.string.contacts_schedule_wednesday,
    Calendar.THURSDAY to R.string.contacts_schedule_thursday,
    Calendar.FRIDAY to R.string.contacts_schedule_friday,
    Calendar.SATURDAY to R.string.contacts_schedule_saturday,
    Calendar.SUNDAY to R.string.contacts_schedule_sunday,
)

@Composable
private fun momentDaysLabel(days: Set<Int>): String = when {
    days.isEmpty() -> stringResource(R.string.contacts_schedule_never)
    days.containsAll(ALL_SCHEDULED_MOMENT_DAYS) -> stringResource(R.string.contacts_schedule_every_day)
    else -> {
        val labels = mutableListOf<String>()
        for ((day, labelRes) in MOMENT_DAY_LABELS) {
            if (day in days) labels += stringResource(labelRes)
        }
        labels.joinToString(" ")
    }
}

@Composable
private fun ScheduledMoment.summary(): String = stringResource(
    R.string.scheduled_moments_summary,
    momentDaysLabel(daysOfWeek),
    minutesOfDay.sorted().joinToString(" ") { formatMinuteOfDay(it) },
    text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
)

private fun newScheduledMoment(): ScheduledMoment = ScheduledMoment(
    id = newScheduledMomentId(),
    text = "",
    minutesOfDay = setOf(currentMinuteOfDay()),
    daysOfWeek = ALL_SCHEDULED_MOMENT_DAYS,
)

fun showScheduledMomentsDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        val moments = remember { ScheduledMomentSchedule.moments.toMutableStateList() }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.scheduled_moments_title)) },
            text = {
                DefaultColumn {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.scheduled_moments_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                showScheduledMomentEditor(
                                    context = context,
                                    titleRes = R.string.scheduled_moments_add_title,
                                    initial = newScheduledMoment(),
                                ) { added -> moments.add(added) }
                            }
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                            Text(stringResource(R.string.scheduled_moments_add))
                        }
                    }

                    if (moments.isEmpty()) {
                        Text(
                            stringResource(R.string.scheduled_moments_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(moments, key = { it.id }) { moment ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        showScheduledMomentEditor(
                                            context = context,
                                            titleRes = R.string.scheduled_moments_edit_title,
                                            initial = moment,
                                        ) { edited ->
                                            val index = moments.indexOfFirst {
                                                it.id == edited.id
                                            }
                                            if (index >= 0) moments[index] = edited
                                        }
                                    },
                                    content = { Text(moment.summary()) },
                                    supportingContent = {
                                        Text(
                                            moment.text.lineSequence()
                                                .filter { it.isNotBlank() }
                                                .joinToString(" / ")
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Switch(
                                                checked = moment.enabled,
                                                onCheckedChange = { enabled ->
                                                    val index = moments.indexOfFirst {
                                                        it.id == moment.id
                                                    }
                                                    if (index >= 0) {
                                                        moments[index] =
                                                            moment.copy(enabled = enabled)
                                                    }
                                                },
                                            )
                                            IconButton(
                                                onClick = { moments.removeAll { it.id == moment.id } }
                                            ) {
                                                Icon(
                                                    MaterialSymbols.Outlined.Delete,
                                                    contentDescription = stringResource(
                                                        R.string.scheduled_moments_delete
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
                    val draft = moments.toList()
                    ScheduledMomentSchedule.mutate { draft }
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

private fun showScheduledMomentEditor(
    context: Context,
    @StringRes titleRes: Int,
    initial: ScheduledMoment,
    onSave: (ScheduledMoment) -> Unit,
) {
    showComposeDialog(context) {
        val localizedContext = LocalXiaoFengLocalizedContext.current
        var draft by remember { mutableStateOf(initial) }

        val textLines = draft.text.lines().count { it.isNotBlank() }
        val canSave = draft.text.isNotBlank() &&
                draft.minutesOfDay.isNotEmpty() &&
                draft.daysOfWeek.isNotEmpty()

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(titleRes)) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = draft.text,
                        onValueChange = { draft = draft.copy(text = it) },
                        label = { Text(stringResource(R.string.scheduled_moments_content)) },
                        placeholder = {
                            Text(stringResource(R.string.scheduled_moments_content_hint))
                        },
                        minLines = 3,
                    )

                    if (textLines >= 2) {
                        ListItem(
                            content = { Text(stringResource(R.string.scheduled_moments_randomize)) },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.scheduled_moments_randomize_description)
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = draft.randomizeLines,
                                    onCheckedChange = {
                                        draft = draft.copy(randomizeLines = it)
                                    },
                                )
                            },
                        )
                    }

                    Text(
                        stringResource(R.string.scheduled_moments_times),
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
                                    draft = draft.copy(minutesOfDay = draft.minutesOfDay - minute)
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
                                                R.string.scheduled_moments_time_picker_title
                                            )
                                        )
                                    },
                                    text = {
                                        DefaultColumn {
                                            WeTimeOfDayField(
                                                modifier = Modifier.fillMaxWidth(),
                                                label = stringResource(
                                                    R.string.contacts_schedule_time
                                                ),
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
                        Text(stringResource(R.string.scheduled_moments_add_time))
                    }

                    if (draft.minutesOfDay.isEmpty()) {
                        Text(
                            stringResource(R.string.scheduled_moments_need_time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MOMENT_DAY_LABELS.forEach { (day, labelRes) ->
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

                    if (draft.text.isBlank()) {
                        Text(
                            stringResource(R.string.scheduled_moments_need_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    TextButton(
                        onClick = {
                            val posted = ScheduledMomentSchedule.postNow(draft)
                            showToast(
                                context,
                                localizedContext.getString(
                                    if (posted) R.string.scheduled_moments_post_success
                                    else R.string.scheduled_moments_post_failure
                                ),
                            )
                        }
                    ) {
                        Text(stringResource(R.string.scheduled_moments_post_now))
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = canSave,
                    onClick = {
                        onSave(draft)
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
