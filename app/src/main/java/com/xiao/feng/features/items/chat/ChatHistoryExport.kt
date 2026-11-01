package com.xiao.feng.features.items.chat

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Calendar_today
import com.composables.icons.materialsymbols.outlined.Date_range
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Text_fields
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.ui.WeContactPrefsScreenApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.contacts.localizedContactsString
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.DropdownOption
import com.xiao.feng.ui.content.m3.DropDownMenuWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.currentWxId
import com.xiao.feng.utils.android.showToastSuspend
import com.xiao.feng.utils.serialization.XmlUtils
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ChatHistoryExport"

private const val EXPORT_BUFFER_SIZE = 64 * 1024

/** How often the export reports its progress back to the dialog. */
private const val PROGRESS_REPORT_INTERVAL = 500

/** Message types that carry plain text: plain text and quoted text. */
@Suppress("DEPRECATION")
private val TEXT_MESSAGE_TYPES = listOf(MessageType.TEXT.code, MessageType.QUOTE.code)

private enum class ExportRange(val days: Long?, @StringRes val labelRes: Int) {
    ALL(null, R.string.chat_history_export_range_all),
    LAST_30(30, R.string.chat_history_export_range_30),
    LAST_7(7, R.string.chat_history_export_range_7),
}

/** Which side of the conversation ends up in the file. */
private enum class SenderFilter(@StringRes val labelRes: Int) {
    ALL(R.string.chat_history_export_senders_all),
    FRIEND(R.string.chat_history_export_senders_friend),
    SELF(R.string.chat_history_export_senders_self),
}

/** Remembered choice: skip images, voice messages, files and other non-text items. */
private var exportTextOnly by prefOption("chat_history_export_only_text", false)

/** Remembered sender filter, persisted as the enum ordinal. */
private var exportSenders by prefOption("chat_history_export_senders", SenderFilter.ALL.ordinal)

/** Remembered choice: emit one heading per day instead of a full timestamp on every line. */
private var exportGroupByDate by prefOption("chat_history_export_group_by_date", true)

private fun rememberedSenderFilter(): SenderFilter =
    SenderFilter.entries.getOrElse(exportSenders) { SenderFilter.ALL }

private data class ExportOptions(
    val range: ExportRange,
    val textOnly: Boolean,
    val senders: SenderFilter,
    val groupByDate: Boolean,
)

/** Row count plus the covered period in epoch milliseconds, for the dialog preview. */
private data class ExportPreview(val count: Long, val firstMillis: Long, val lastMillis: Long)

/** Per-friend chat history export entry inside the friend profile screen. */
object ChatHistoryExport : SwitchFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "聊天记录导出"
    override val nameRes = R.string.feature_chat_history_export_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_chat_history_export_description

    override val defaultEnabled = true

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (activity.javaClass.name != ContactInfoUI::class.java.name) return emptyList()
        val wxId = activity.currentWxId ?: return emptyList()
        // Individual friends only: groups and official accounts are not exportable here.
        if (wxId.isGroupChatWxId || wxId.startsWith("gh_")) return emptyList()
        if (!WeDatabaseApi.isReady || WeDatabaseApi.getFriend(wxId) == null) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(R.string.feature_chat_history_export_name),
                summary = activity.localizedContactsString(R.string.chat_history_export_entry_summary),
                position = 1,
                onClick = onClick@{ itemActivity ->
                    showChatHistoryExportDialog(itemActivity, wxId)
                },
            )
        )
    }
}

fun showChatHistoryExportDialog(context: Context, wxId: String) {
    showComposeDialog(context) {
        val hostDialog = dialog
        var range by remember { mutableStateOf(ExportRange.ALL) }
        var senders by remember { mutableStateOf(rememberedSenderFilter()) }
        var textOnly by remember { mutableStateOf(exportTextOnly) }
        var groupByDate by remember { mutableStateOf(exportGroupByDate) }
        var displayName by remember(wxId) { mutableStateOf("") }
        var preview by remember(wxId) { mutableStateOf<ExportPreview?>(null) }
        var exporting by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0) }
        var result by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val coverageDateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

        LaunchedEffect(wxId) {
            displayName = withContext(Dispatchers.IO) { WeDatabaseApi.getDisplayName(wxId) }
        }
        LaunchedEffect(wxId, range, senders, textOnly) {
            preview = null
            preview = withContext(Dispatchers.IO) {
                loadPreview(wxId, range, senders, textOnly)
            }
        }
        // Dismissing mid-export would leave a partially written file behind.
        LaunchedEffect(exporting) { hostDialog.setCancelable(!exporting) }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = {
                Text(
                    stringResource(
                        R.string.chat_history_export_title,
                        displayName.ifBlank { wxId },
                    )
                )
            },
            text = {
                DefaultColumn {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            DropDownMenuWidget(
                                icon = MaterialSymbols.Outlined.Date_range,
                                title = stringResource(R.string.chat_history_export_range),
                                description = null,
                                value = range,
                                options = ExportRange.entries.map {
                                    DropdownOption(it, stringResource(it.labelRes))
                                },
                                enabled = !exporting,
                                onValueChange = {
                                    range = it
                                    result = null
                                },
                            )
                        }
                        item {
                            DropDownMenuWidget(
                                icon = MaterialSymbols.Outlined.Person,
                                title = stringResource(R.string.chat_history_export_senders),
                                description = null,
                                value = senders,
                                options = SenderFilter.entries.map {
                                    DropdownOption(it, stringResource(it.labelRes))
                                },
                                enabled = !exporting,
                                onValueChange = {
                                    senders = it
                                    exportSenders = it.ordinal
                                    result = null
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Text_fields,
                                title = stringResource(R.string.chat_history_export_only_text),
                                description = stringResource(R.string.chat_history_export_only_text_description),
                                enabled = !exporting,
                                checked = textOnly,
                                onCheckedChange = {
                                    textOnly = it
                                    exportTextOnly = it
                                    result = null
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                icon = MaterialSymbols.Outlined.Calendar_today,
                                title = stringResource(R.string.chat_history_export_group_by_date),
                                description = stringResource(R.string.chat_history_export_group_by_date_description),
                                enabled = !exporting,
                                checked = groupByDate,
                                onCheckedChange = {
                                    groupByDate = it
                                    exportGroupByDate = it
                                    result = null
                                },
                            )
                        }
                    }
                    val currentPreview = preview
                    val count = currentPreview?.count
                    val statusText = when {
                        exporting -> stringResource(
                            R.string.chat_history_export_progress,
                            progress,
                            count ?: progress,
                        )
                        count == null -> stringResource(R.string.chat_history_export_counting)
                        count == 0L -> stringResource(R.string.chat_history_export_empty)
                        else -> stringResource(R.string.chat_history_export_message_count, count)
                    }
                    if (exporting) {
                        LinearProgressIndicator(
                            progress = {
                                val total = count ?: 0L
                                if (total <= 0L) 0f
                                else (progress.toFloat() / total).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (currentPreview != null && currentPreview.count > 0 && !exporting) {
                        Text(
                            text = stringResource(
                                R.string.chat_history_export_coverage,
                                coverageDateFormat.format(Date(currentPreview.firstMillis)),
                                coverageDateFormat.format(Date(currentPreview.lastMillis)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    result?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !exporting && preview?.count != null && preview?.count != 0L,
                    onClick = {
                        exporting = true
                        progress = 0
                        result = null
                        val options = ExportOptions(range, textOnly, senders, groupByDate)
                        scope.launch {
                            val exported = withContext(Dispatchers.IO) {
                                exportChatHistory(context, wxId, displayName, options) { done ->
                                    scope.launch { progress = done }
                                }
                            }
                            exporting = false
                            val text = if (exported == null) {
                                context.localizedChatString(R.string.chat_history_export_failed)
                            } else {
                                context.localizedChatString(
                                    R.string.chat_history_export_success,
                                    exported.first,
                                    exported.second,
                                )
                            }
                            result = text
                            showToastSuspend(context, text)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (exporting) R.string.chat_history_export_in_progress
                            else R.string.chat_history_export_button
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss, enabled = !exporting) {
                    Text(stringResource(R.string.dialog_close))
                }
            },
        )
    }
}

/** Epoch values below this magnitude are seconds; anything above is milliseconds. */
private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L

/**
 * The `message.createTime` column is seconds on some supported hosts and milliseconds on others.
 * The scale is a property of the database, so it is detected once and cached for the process.
 */
@Volatile
private var createTimeScale: Long? = null

private fun createTimeScale(wxId: String): Long {
    createTimeScale?.let { return it }
    val latest = WeDatabaseApi.rawQuery(
        "SELECT MAX(createTime) FROM message WHERE talker = ?",
        arrayOf<Any>(wxId),
    ).use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    }
    if (latest <= 0L) return 1000L
    val scale = if (latest < EPOCH_MILLIS_THRESHOLD) 1000L else 1L
    createTimeScale = scale
    return scale
}

/** Lower bound of the selected range in the database's own time unit. */
private fun rangeSince(range: ExportRange, scale: Long): Long? =
    range.days?.let { (System.currentTimeMillis() - it * 86_400_000L) / scale }

/** Messages matching the current selection, for the dialog preview. */
private fun loadPreview(
    wxId: String,
    range: ExportRange,
    senders: SenderFilter,
    textOnly: Boolean,
): ExportPreview {
    val scale = createTimeScale(wxId)
    val since = rangeSince(range, scale)
    val sql = buildString {
        append("SELECT COUNT(*), MIN(createTime), MAX(createTime) FROM message WHERE talker = ?")
        appendExportConditions(since, senders, textOnly)
    }
    // Only a single row is read here; going through rawQuery avoids building a result map.
    return WeDatabaseApi.rawQuery(sql, arrayOf<Any>(wxId)).use { cursor ->
        if (cursor.moveToFirst()) {
            // Normalize both bounds to milliseconds so the dialog does not need the scale.
            ExportPreview(
                cursor.getLong(0),
                cursor.getLong(1) * scale,
                cursor.getLong(2) * scale,
            )
        } else {
            ExportPreview(0, 0, 0)
        }
    }
}

/**
 * Conditions shared by the count preview and the export so both always select the same rows.
 * [since] is a computed value in the database's time unit, so it is written directly: a numeric
 * bind argument was not reliably applied to `message.createTime` comparisons on device.
 */
private fun StringBuilder.appendExportConditions(
    since: Long?,
    senders: SenderFilter,
    textOnly: Boolean,
) {
    if (since != null) append(" AND createTime >= $since")
    when (senders) {
        SenderFilter.ALL -> Unit
        SenderFilter.FRIEND -> append(" AND isSend = 0")
        SenderFilter.SELF -> append(" AND isSend = 1")
    }
    if (textOnly) append(" AND type IN (${TEXT_MESSAGE_TYPES.joinToString(",")})")
}

private class ExportTarget(
    val path: String,
    val stream: OutputStream,
    val onSuccess: () -> Unit,
    val onFailure: () -> Unit,
)

/**
 * Opens the destination file. Android 10+ goes through MediaStore Downloads so no runtime storage
 * permission is needed; Android 9 falls back to the app's own external files directory.
 */
private fun openExportTarget(context: Context, fileName: String): ExportTarget {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/XiaoFeng")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        return ExportTarget(
            path = "${Environment.DIRECTORY_DOWNLOADS}/XiaoFeng/$fileName",
            stream = resolver.openOutputStream(uri)!!,
            onSuccess = {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            },
            onFailure = { resolver.delete(uri, null, null) },
        )
    }

    val dir = File(context.getExternalFilesDir(null), "XiaoFeng").apply { mkdirs() }
    val file = File(dir, fileName)
    return ExportTarget(
        path = file.absolutePath,
        stream = FileOutputStream(file),
        onSuccess = {},
        onFailure = { file.delete() },
    )
}

/** Writes the chat history and returns the visible path and exported message count, or null. */
private fun exportChatHistory(
    context: Context,
    wxId: String,
    displayName: String,
    options: ExportOptions,
    onProgress: (Int) -> Unit,
): Pair<String, Int>? {
    val scale = createTimeScale(wxId)
    val since = rangeSince(options.range, scale)
    val sql = buildString {
        append("SELECT type, content, createTime, isSend FROM message WHERE talker = ?")
        appendExportConditions(since, options.senders, options.textOnly)
        append(" ORDER BY createTime ASC")
    }

    val fullTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(40)
    val fileName = "${safeName}_$stamp.txt"

    val target = try {
        openExportTarget(context, fileName)
    } catch (ex: Exception) {
        WeLogger.e(TAG, "failed to create export target; wxid=$wxId", ex)
        return null
    }
    var count = 0
    try {
        // Explicit UTF-8 and a larger buffer: a long history is written in a single pass.
        target.stream.writer(Charsets.UTF_8).buffered(EXPORT_BUFFER_SIZE).use { writer ->
            writer.write(
                buildString {
                    appendLine(context.localizedChatString(R.string.chat_history_export_header_title))
                    appendLine(
                        context.localizedChatString(
                            R.string.chat_history_export_header_contact,
                            displayName,
                            wxId,
                        )
                    )
                    appendLine(
                        context.localizedChatString(
                            R.string.chat_history_export_header_time,
                            fullTimeFormat.format(Date()),
                        )
                    )
                    appendLine(
                        context.localizedChatString(
                            R.string.chat_history_export_header_range,
                            context.localizedChatString(options.range.labelRes),
                        )
                    )
                    if (options.senders != SenderFilter.ALL) {
                        appendLine(
                            context.localizedChatString(
                                R.string.chat_history_export_header_sender,
                                context.localizedChatString(options.senders.labelRes),
                            )
                        )
                    }
                    if (options.textOnly) {
                        appendLine(
                            context.localizedChatString(R.string.chat_history_export_header_text_only)
                        )
                    }
                    appendLine()
                }
            )

            val selfLabel = context.localizedChatString(R.string.chat_history_export_self)
            // Non-text rows repeat the same handful of type labels; resolve each one once.
            val typeLabels = HashMap<Int, String>()
            // One Date instance is reused: formatting a fresh Date per message allocates on
            // every row of a potentially long history.
            val date = Date()
            var currentDay: String? = null
            WeDatabaseApi.rawQuery(sql, arrayOf<Any>(wxId)).use { cursor ->
                while (cursor.moveToNext()) {
                    date.time = cursor.getLong(2) * scale
                    val sender = if (cursor.getInt(3) == 1) selfLabel else displayName
                    val body = messageBody(context, cursor, typeLabels)
                    if (options.groupByDate) {
                        val day = dateFormat.format(date)
                        if (day != currentDay) {
                            currentDay = day
                            writer.write(if (count == 0) "$day\n" else "\n$day\n")
                        }
                        writer.write("[${clockFormat.format(date)}] $sender: $body\n")
                    } else {
                        writer.write("[${fullTimeFormat.format(date)}] $sender: $body\n")
                    }
                    count++
                    if (count % PROGRESS_REPORT_INTERVAL == 0) onProgress(count)
                }
            }
        }
        target.onSuccess()
    } catch (ex: Exception) {
        WeLogger.e(TAG, "failed to export chat history; wxid=$wxId", ex)
        runCatching { target.stream.close() }
        runCatching { target.onFailure() }
        return null
    }

    return target.path to count
}

private fun messageBody(
    context: Context,
    cursor: Cursor,
    typeLabels: MutableMap<Int, String>,
): String {
    val type = cursor.getInt(0)
    val content = cursor.getString(1) ?: ""
    val messageType = MessageType.fromCode(type)

    if (messageType?.isText == true) {
        return if (content.startsWith("<msg")) {
            XmlUtils.extractXmlTag(content, "title").ifEmpty { content }
        } else {
            content
        }
    }

    if (type == MessageType.APP.code) {
        val title = XmlUtils.extractXmlTag(content, "title")
        if (title.isNotEmpty()) return title
    }

    return typeLabels.getOrPut(type) {
        val label = messageType?.let { context.localizedChatString(it.displayNameRes) }
            ?: context.localizedChatString(R.string.chat_history_export_other_message)
        "[$label]"
    }
}
