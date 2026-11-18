package com.xiao.feng.features.items.contacts

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.xiao.feng.R
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 目标选择列表: 操作间隔 + 可选的「新建标签」+ 按类别分组的已有目标。
 *
 * 只负责展示与回调, 由调用方决定外层对话框的标题和按钮。
 */
@Composable
fun ContactCollectionTargetList(
    targets: List<ContactCollector.Target>,
    allowNewLabel: Boolean,
    intervalInput: String,
    onIntervalChange: (String) -> Unit,
    onPick: (ContactCollector.Target) -> Unit,
    newLabelDefault: String = "",
) {
    DefaultColumn {
        OutlinedTextField(
            value = intervalInput,
            onValueChange = { onIntervalChange(sanitizeDecimalInput(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.contact_collection_interval)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )

        if (allowNewLabel) {
            var newLabelName by remember { mutableStateOf(newLabelDefault) }
            OutlinedTextField(
                value = newLabelName,
                onValueChange = { newLabelName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.contact_collection_new_label_hint)) },
                singleLine = true,
            )
            Button(
                enabled = newLabelName.isNotBlank(),
                onClick = {
                    onPick(
                        ContactCollector.Target(
                            kind = ContactCollector.TargetKind.LABEL,
                            id = "",
                            name = newLabelName.trim(),
                        )
                    )
                },
            ) { Text(stringResource(R.string.contact_collection_new_label_apply)) }
        }

        if (targets.isEmpty()) {
            Text(stringResource(R.string.contact_collection_empty))
        } else {
            LazyColumn {
                ContactCollector.TargetKind.entries.forEach { kind ->
                    val group = targets.filter { it.kind == kind }
                    if (group.isEmpty()) return@forEach
                    item(key = "header_${kind.name}") {
                        Text(
                            text = stringResource(kind.sectionLabelRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(group, key = { "${it.kind.name}_${it.id}_${it.name}" }) { target ->
                        ListItem(
                            modifier = Modifier.clickable { onPick(target) },
                            content = { Text(target.name) },
                        )
                    }
                }
            }
        }
    }
}

private val ContactCollector.TargetKind.sectionLabelRes: Int
    get() = when (this) {
        ContactCollector.TargetKind.LABEL -> R.string.contact_collection_section_label
        ContactCollector.TargetKind.FOLDER -> R.string.contact_collection_section_folder
        ContactCollector.TargetKind.GROUP -> R.string.contact_collection_section_group
    }

/** 只保留数字与一个小数点, 避免把非法字符写进间隔配置。 */
fun sanitizeDecimalInput(input: String): String = buildString {
    var hasDecimalPoint = false
    input.forEach { char ->
        when {
            char.isDigit() -> append(char)
            char == '.' && !hasDecimalPoint -> {
                if (isEmpty()) append('0')
                append(char)
                hasDecimalPoint = true
            }
        }
    }
}

/**
 * 打开「群成员批量操作」对话框: 读取群内好友 -> 选择目标 -> 按间隔逐个执行。
 *
 * @param onIntervalPicked 用户确认目标时把当前间隔写回功能自己的配置。
 */
fun Context.startGroupCollection(
    title: String,
    groupWxId: String,
    includeLabels: Boolean,
    includeCollections: Boolean,
    initialInterval: String,
    onIntervalPicked: (String) -> Unit,
) {
    showComposeDialog(this) {
        var members by remember { mutableStateOf<List<WeContact>?>(null) }
        var targets by remember { mutableStateOf<List<ContactCollector.Target>?>(null) }
        var intervalInput by remember { mutableStateOf(initialInterval) }
        var runningTarget by remember { mutableStateOf<ContactCollector.Target?>(null) }
        var done by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val completed = remember { mutableIntStateOf(0) }
        val completedValue by completed

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val loadedMembers = ContactCollector.friendMembersOf(groupWxId)
                val loadedTargets =
                    ContactCollector.loadTargets(this@startGroupCollection, includeLabels, includeCollections)
                members = loadedMembers
                targets = loadedTargets
            }
        }

        LaunchedEffect(runningTarget) {
            val target = runningTarget ?: return@LaunchedEffect
            val ids = members.orEmpty().map { it.wxId }
            onIntervalPicked(intervalInput)
            val interval = ContactCollector.intervalMillis(intervalInput)
            // 批量执行期间禁止外部点击/返回键中断, 避免用户误触后只处理了一半。
            dialog.setCancelable(false)
            val ok = try {
                withContext(Dispatchers.IO) {
                    if (!ContactCollector.prepare(target)) return@withContext false
                    ids.forEachIndexed { index, wxId ->
                        ContactCollector.apply(target, wxId)
                        completed.intValue = index + 1
                        if (index < ids.lastIndex && interval > 0) delay(interval)
                    }
                    true
                }
            } finally {
                dialog.setCancelable(true)
            }
            failed = !ok
            done = true
        }

        AlertDialogContent(
            title = {
                Text(
                    if (done) stringResource(R.string.contact_collection_done_title) else title
                )
            },
            text = {
                val currentMembers = members
                val currentTargets = targets
                when {
                    done -> Text(
                        if (failed) {
                            stringResource(
                                R.string.contact_collection_prepare_failed,
                                runningTarget?.name.orEmpty(),
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.contact_collection_done,
                                completedValue,
                                completedValue,
                                currentMembers.orEmpty().size,
                                runningTarget?.name.orEmpty(),
                            )
                        }
                    )

                    runningTarget != null -> DefaultColumn {
                        val total = currentMembers.orEmpty().size
                        Text(
                            pluralStringResource(
                                R.plurals.contact_collection_progress,
                                total,
                                runningTarget?.name.orEmpty(),
                                completedValue,
                                total,
                            )
                        )
                        LinearWavyProgressIndicator(
                            progress = {
                                if (total == 0) 1f else completedValue.toFloat() / total
                            }
                        )
                    }

                    currentMembers == null || currentTargets == null -> LinearWavyProgressIndicator()

                    currentMembers.isEmpty() -> Text(
                        stringResource(R.string.batch_group_members_no_friends)
                    )

                    else -> ContactCollectionTargetList(
                        targets = currentTargets,
                        allowNewLabel = includeLabels,
                        intervalInput = intervalInput,
                        onIntervalChange = { intervalInput = it },
                        onPick = { runningTarget = it },
                    )
                }
            },
            dismissButton = if (runningTarget == null) {
                { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            } else null,
            confirmButton = if (done) {
                { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            } else null,
        )
    }
}
