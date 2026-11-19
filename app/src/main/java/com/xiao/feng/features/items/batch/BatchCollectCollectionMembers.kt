package com.xiao.feng.features.items.batch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearWavyProgressIndicator
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
import androidx.compose.ui.text.input.KeyboardType
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.contacts.ContactCollector
import com.xiao.feng.features.items.contacts.pickContactTarget
import com.xiao.feng.features.items.contacts.sanitizeDecimalInput
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 把选择的归拢/分组里所有群聊的成员 (仅已是好友的) 批量归拢到另一个归拢/分组。
 */
object BatchCollectCollectionMembers : ClickableFeature() {

    override val technicalId = "归拢分组群成员批量归拢"
    override val nameRes = R.string.feature_batch_collect_collection_members_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_collect_collection_members_description
    override val noSwitchWidget = true

    private const val KEY_INTERVAL = "batch_collect_collection_interval_seconds"

    private var intervalSeconds by KvStore.prefOption(KEY_INTERVAL, "1")

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var collections by remember { mutableStateOf<List<ContactCollector.Target>?>(null) }
            var source by remember { mutableStateOf<ContactCollector.Target?>(null) }
            var target by remember { mutableStateOf<ContactCollector.Target?>(null) }
            var intervalInput by remember { mutableStateOf(intervalSeconds) }
            var running by remember { mutableStateOf(false) }
            var done by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            var completed by remember { mutableIntStateOf(0) }
            var total by remember { mutableIntStateOf(0) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    collections = ContactCollector.loadTargets(
                        context,
                        includeLabels = false,
                        includeCollections = true,
                    )
                }
            }

            LaunchedEffect(running) {
                if (!running) return@LaunchedEffect
                val from = source ?: return@LaunchedEffect
                val to = target ?: return@LaunchedEffect
                intervalSeconds = intervalInput
                val interval = ContactCollector.intervalMillis(intervalInput)
                dialog.setCancelable(false)
                val ok = try {
                    withContext(Dispatchers.IO) {
                        if (!ContactCollector.prepare(to)) return@withContext false
                        val ids = ContactCollector.friendMembersOfCollection(from)
                        total = ids.size
                        ids.forEachIndexed { index, wxId ->
                            ContactCollector.apply(to, wxId)
                            completed = index + 1
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
                title = { Text(stringResource(R.string.feature_batch_collect_collection_members_name)) },
                text = {
                    DefaultColumn {
                        when {
                            done -> Text(
                                if (failed) stringResource(R.string.contact_collection_prepare_failed)
                                else stringResource(R.string.batch_collect_collection_done, completed),
                            )

                            running -> {
                                Text(stringResource(R.string.batch_collect_collection_running, completed, total))
                                LinearWavyProgressIndicator(
                                    progress = { if (total == 0) 0f else completed.toFloat() / total },
                                )
                            }

                            else -> {
                                OutlinedTextField(
                                    value = intervalInput,
                                    onValueChange = { intervalInput = sanitizeDecimalInput(it) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.contact_collection_interval)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                )
                                BaseWidget(
                                    title = stringResource(R.string.batch_collect_source),
                                    description = source?.name,
                                    onClick = {
                                        collections?.let { options ->
                                            context.pickContactTarget(
                                                title = context.localizedBatchString(R.string.batch_collect_source),
                                                targets = options,
                                                onPick = { source = it },
                                            )
                                        }
                                    },
                                )
                                BaseWidget(
                                    title = stringResource(R.string.batch_collect_target),
                                    description = target?.name,
                                    onClick = {
                                        collections?.let { options ->
                                            context.pickContactTarget(
                                                title = context.localizedBatchString(R.string.batch_collect_target),
                                                targets = options,
                                                onPick = { target = it },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = if (running) null else {
                    {
                        TextButton(onDismiss) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                },
                confirmButton = when {
                    done -> {
                        {
                            Button(onDismiss) {
                                Text(stringResource(R.string.dialog_close))
                            }
                        }
                    }

                    running -> null

                    else -> {
                        {
                            Button(
                                onClick = {
                                    completed = 0
                                    failed = false
                                    running = true
                                },
                                enabled = source != null && target != null,
                            ) {
                                Text(stringResource(R.string.dialog_confirm))
                            }
                        }
                    }
                },
            )
        }
    }
}
