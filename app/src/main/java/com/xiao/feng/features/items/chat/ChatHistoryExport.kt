package com.xiao.feng.features.items.chat

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Calendar_today
import com.composables.icons.materialsymbols.outlined.Date_range
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlined.Text_fields
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
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
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseSupportingWidget
import com.xiao.feng.ui.content.m3.BaseWidget
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val TAG = "ChatHistoryExport"

private const val EXPORT_BUFFER_SIZE = 64 * 1024

/** How often the export reports its progress back to the dialog. */
private const val PROGRESS_REPORT_INTERVAL = 500

private const val EXPORT_DAY_MILLIS = 86_400_000L

/** Default custom window when the user has not picked dates yet: the last 30 days. */
private const val CUSTOM_RANGE_SPAN_DAYS = 29L

/** Message types that carry plain text: plain text and quoted text. */
@Suppress("DEPRECATION")
private val TEXT_MESSAGE_TYPES = listOf(MessageType.TEXT.code, MessageType.QUOTE.code)

/** 群消息 content 形如 `wxid_xxx:\n正文`; 该前缀必须是合法的微信用户名。 */
private val GROUP_SENDER_PREFIX = Regex("""^[A-Za-z0-9_-]{5,64}${'$'}""")

private enum class RangePreset(val days: Long?, @StringRes val labelRes: Int) {
    ALL(null, R.string.chat_history_export_range_all),
    LAST_30(30, R.string.chat_history_export_range_30),
    LAST_7(7, R.string.chat_history_export_range_7),
    CUSTOM(null, R.string.chat_history_export_range_custom),
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

/** Remembered range preset and the custom day bounds, persisted as epoch days. */
private var exportRangePreset by prefOption("chat_history_export_range_preset", RangePreset.ALL.ordinal)
private var exportCustomStartDay by prefOption("chat_history_export_custom_start_day", -1L)
private var exportCustomEndDay by prefOption("chat_history_export_custom_end_day", -1L)

private fun rememberedPreset(): RangePreset =
    RangePreset.entries.getOrElse(exportRangePreset) { RangePreset.ALL }

private fun rememberedSenderFilter(): SenderFilter =
    SenderFilter.entries.getOrElse(exportSenders) { SenderFilter.ALL }

/**
 * Selected export window. [startMillis] is inclusive and [endMillisExclusive] is exclusive; both
 * are epoch milliseconds in the local time zone, or null for an unbounded side.
 */
private data class ExportWindow(val startMillis: Long?, val endMillisExclusive: Long?) {

    val isValid: Boolean
        get() = startMillis == null ||
                endMillisExclusive == null ||
                startMillis < endMillisExclusive
}

private data class ExportOptions(
    val window: ExportWindow,
    val preset: RangePreset,
    val textOnly: Boolean,
    val senders: SenderFilter,
    val groupByDate: Boolean,
    val keyword: String,
)

/** Row count plus the covered period in epoch milliseconds, for the dialog preview. */
private data class ExportPreview(val count: Long, val firstMillis: Long, val lastMillis: Long)

private data class RenderedMessage(val sender: String, val body: String)

private fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

private fun dayStartMillis(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

private fun millisToEpochDay(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

private fun windowFor(preset: RangePreset, startDay: Long, endDay: Long): ExportWindow = when (preset) {
    RangePreset.ALL -> ExportWindow(null, null)
    RangePreset.LAST_7 -> ExportWindow(System.currentTimeMillis() - 7 * EXPORT_DAY_MILLIS, null)
    RangePreset.LAST_30 -> ExportWindow(System.currentTimeMillis() - 30 * EXPORT_DAY_MILLIS, null)
    // End bound is the start of the day after endDay, so the whole selected day is included
    // even when the local day length differs from 24 hours (DST transitions).
    RangePreset.CUSTOM -> ExportWindow(
        startDay.takeIf { it >= 0 }?.let(::dayStartMillis),
        endDay.takeIf { it >= 0 }?.let { dayStartMillis(LocalDate.ofEpochDay(it).plusDays(1).toEpochDay()) },
    )
}

/** Per-friend and per-group chat history export entry, shown on the respective settings screen. */
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
        if (!WeDatabaseApi.isReady) return emptyList()
        val wxId = activity.currentWxId ?: return emptyList()

        return when (activity.javaClass.name) {
            ContactInfoUI::class.java.name -> friendItem(activity, wxId)
            ChatroomInfoUI::class.java.name -> groupItem(activity, wxId)
            else -> emptyList()
        }
    }

    private fun friendItem(
        activity: Activity,
        wxId: String,
    ): List<WeContactPrefsScreenApi.PreferenceItem> {
        // Individual friends only: official accounts are not exportable here.
        if (wxId.isGroupChatWxId || wxId.startsWith("gh_")) return emptyList()
        if (WeDatabaseApi.getFriend(wxId) == null) return emptyList()

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

    private fun groupItem(
        activity: Activity,
        groupId: String,
    ): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (!groupId.isGroupChatWxId) return emptyList()
        if (WeDatabaseApi.getGroup(groupId) == null) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                title = activity.localizedContactsString(R.string.feature_chat_history_export_name),
                summary = activity.localizedContactsString(R.string.chat_history_export_entry_summary_group),
                position = 4,
                onClick = onClick@{ itemActivity ->
                    showChatHistoryExportDialog(itemActivity, groupId)
                },
            )
        )
    }
}

fun showChatHistoryExportDialog(context: Context, wxId: String) {
    showComposeDialog(context) {
        val hostDialog = dialog
        val isGroupChat = wxId.isGroupChatWxId
        var preset by remember { mutableStateOf(rememberedPreset()) }
        var customStartDay by remember { mutableStateOf(exportCustomStartDay) }
        var customEndDay by remember { mutableStateOf(exportCustomEndDay) }
        val window = remember(preset, customStartDay, customEndDay) {
            windowFor(preset, customStartDay, customEndDay)
        }
        var senders by remember { mutableStateOf(rememberedSenderFilter()) }
        var textOnly by remember { mutableStateOf(exportTextOnly) }
        var groupByDate by remember { mutableStateOf(exportGroupByDate) }
        var keyword by remember { mutableStateOf("") }
        var displayName by remember(wxId) { mutableStateOf("") }
        var preview by remember(wxId) { mutableStateOf<ExportPreview?>(null) }
        var exporting by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0) }
        var result by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val coverageDateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

        fun rememberCustomWindow() {
            if (customStartDay < 0 && customEndDay < 0) {
                val today = todayEpochDay()
                customStartDay = today - CUSTOM_RANGE_SPAN_DAYS
                customEndDay = today
                exportCustomStartDay = customStartDay
                exportCustomEndDay = customEndDay
            }
        }

        LaunchedEffect(wxId) {
            displayName = withContext(Dispatchers.IO) { WeDatabaseApi.getDisplayName(wxId) }
        }
        LaunchedEffect(wxId, window, senders, textOnly, keyword) {
            preview = null
            // Typing in the keyword field should not run one query per keystroke.
            if (keyword.isNotEmpty()) delay(300)
            preview = withContext(Dispatchers.IO) {
                loadPreview(wxId, window, senders, textOnly, keyword.trim())
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
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            DropDownMenuWidget(
                                icon = MaterialSymbols.Outlined.Date_range,
                                title = stringResource(R.string.chat_history_export_range),
                                description = null,
                                value = preset,
                                options = RangePreset.entries.map {
                                    DropdownOption(it, stringResource(it.labelRes))
                                },
                                enabled = !exporting,
                                onValueChange = {
                                    preset = it
                                    exportRangePreset = it.ordinal
                                    if (it == RangePreset.CUSTOM) rememberCustomWindow()
                                    result = null
                                },
                            )
                        }
                        if (preset == RangePreset.CUSTOM) {
                            item {
                                ExportDateRow(
                                    title = stringResource(R.string.chat_history_export_start_date),
                                    epochDay = customStartDay,
                                    enabled = !exporting,
                                ) {
                                    customStartDay = it
                                    exportCustomStartDay = it
                                    result = null
                                }
                            }
                            item {
                                ExportDateRow(
                                    title = stringResource(R.string.chat_history_export_end_date),
                                    epochDay = customEndDay,
                                    enabled = !exporting,
                                ) {
                                    customEndDay = it
                                    exportCustomEndDay = it
                                    result = null
                                }
                            }
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
                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.chat_history_export_keyword),
                            ) {
                                OutlinedTextField(
                                    value = keyword,
                                    onValueChange = {
                                        keyword = it
                                        result = null
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    enabled = !exporting,
                                    singleLine = true,
                                    placeholder = {
                                        Text(stringResource(R.string.chat_history_export_keyword_placeholder))
                                    },
                                )
                            }
                        }
                    }

                    Column(Modifier.padding(top = 12.dp)) {
                        val currentPreview = preview
                        val count = currentPreview?.count
                        val statusText = when {
                            exporting -> stringResource(
                                R.string.chat_history_export_progress,
                                progress,
                                count ?: progress,
                            )
                            !window.isValid -> stringResource(R.string.chat_history_export_invalid_range)
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
                }
            },
            confirmButton = {
                Button(
                    enabled = !exporting && window.isValid &&
                            preview?.count != null && preview?.count != 0L,
                    onClick = {
                        exporting = true
                        progress = 0
                        result = null
                        val options = ExportOptions(
                            window = window,
                            preset = preset,
                            textOnly = textOnly,
                            senders = senders,
                            groupByDate = groupByDate,
                            keyword = keyword.trim(),
                        )
                        scope.launch {
                            val exported = withContext(Dispatchers.IO) {
                                exportChatHistory(
                                    context = context,
                                    wxId = wxId,
                                    displayName = displayName,
                                    isGroupChat = isGroupChat,
                                    options = options,
                                ) { done ->
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

@Composable
private fun ExportDateRow(
    title: String,
    epochDay: Long,
    enabled: Boolean,
    onPick: (Long) -> Unit,
) {
    val context = LocalContext.current
    val description = if (epochDay >= 0) {
        formatEpochDay(epochDay)
    } else {
        stringResource(R.string.chat_history_export_date_unset)
    }

    BaseWidget(
        icon = MaterialSymbols.Outlined.Calendar_today,
        title = title,
        description = description,
        enabled = enabled,
        onClick = {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = if (epochDay >= 0) dayStartMillis(epochDay) else System.currentTimeMillis()
            }
            DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    onPick(LocalDate.of(year, month + 1, dayOfMonth).toEpochDay())
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        },
    )
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

/** Bounds of the selected window in the database's own time unit. */
private fun ExportWindow.bounds(scale: Long): Pair<Long?, Long?> =
    startMillis?.div(scale) to endMillisExclusive?.div(scale)

/** Messages matching the current selection, for the dialog preview. */
private fun loadPreview(
    wxId: String,
    window: ExportWindow,
    senders: SenderFilter,
    textOnly: Boolean,
    keyword: String,
): ExportPreview {
    val scale = createTimeScale(wxId)
    val (since, until) = window.bounds(scale)
    val sql = buildString {
        append("SELECT COUNT(*), MIN(createTime), MAX(createTime) FROM message WHERE talker = ?")
        appendExportConditions(since, until, senders, textOnly, keyword)
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
 * [since] and [until] are computed values in the database's time unit, so they are written
 * directly: a numeric bind argument was not reliably applied to `message.createTime` comparisons
 * on device.
 */
private fun StringBuilder.appendExportConditions(
    since: Long?,
    until: Long?,
    senders: SenderFilter,
    textOnly: Boolean,
    keyword: String,
) {
    if (since != null) append(" AND createTime >= $since")
    if (until != null) append(" AND createTime < $until")
    when (senders) {
        SenderFilter.ALL -> Unit
        SenderFilter.FRIEND -> append(" AND isSend = 0")
        SenderFilter.SELF -> append(" AND isSend = 1")
    }
    if (textOnly) append(" AND type IN (${TEXT_MESSAGE_TYPES.joinToString(",")})")
    if (keyword.isNotEmpty()) {
        val escaped = keyword.replace("'", "''")
        append(" AND content LIKE '%$escaped%'")
    }
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
    isGroupChat: Boolean,
    options: ExportOptions,
    onProgress: (Int) -> Unit,
): Pair<String, Int>? {
    val scale = createTimeScale(wxId)
    val (since, until) = options.window.bounds(scale)
    val displayNameOrId = displayName.ifBlank { wxId }
    // Group messages carry the sender's wxId in the content prefix; resolve it to a display name.
    val senderNames = if (isGroupChat) loadGroupSenderNames(wxId) else emptyMap()
    val formatter = MessageBodyFormatter(context, isGroupChat, displayNameOrId, senderNames)

    val sql = buildString {
        append("SELECT type, content, createTime, isSend FROM message WHERE talker = ?")
        appendExportConditions(since, until, options.senders, options.textOnly, options.keyword)
        append(" ORDER BY createTime ASC")
    }

    val fullTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val safeName = displayNameOrId.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(40)
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
                            if (isGroupChat) R.string.chat_history_export_header_group
                            else R.string.chat_history_export_header_contact,
                            displayNameOrId,
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
                            exportWindowLabel(context, options),
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
                    if (options.keyword.isNotEmpty()) {
                        appendLine(
                            context.localizedChatString(
                                R.string.chat_history_export_header_keyword,
                                options.keyword,
                            )
                        )
                    }
                    appendLine()
                }
            )

            // One Date instance is reused: formatting a fresh Date per message allocates on
            // every row of a potentially long history.
            val date = Date()
            var currentDay: String? = null
            WeDatabaseApi.rawQuery(sql, arrayOf<Any>(wxId)).use { cursor ->
                while (cursor.moveToNext()) {
                    date.time = cursor.getLong(2) * scale
                    val rendered = formatter.format(
                        type = cursor.getInt(0),
                        rawContent = cursor.getString(1).orEmpty(),
                        isSend = cursor.getInt(3),
                    )
                    if (options.groupByDate) {
                        val day = dateFormat.format(date)
                        if (day != currentDay) {
                            currentDay = day
                            writer.write(if (count == 0) "$day\n" else "\n$day\n")
                        }
                        writer.write("[${clockFormat.format(date)}] ${rendered.sender}: ${rendered.body}\n")
                    } else {
                        writer.write("[${fullTimeFormat.format(date)}] ${rendered.sender}: ${rendered.body}\n")
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

/** Human-readable description of the selected window for the file header. */
private fun exportWindowLabel(context: Context, options: ExportOptions): String {
    if (options.preset != RangePreset.CUSTOM) {
        return context.localizedChatString(options.preset.labelRes)
    }
    val startDay = options.window.startMillis?.let(::millisToEpochDay)
    // The exclusive bound is the start of the next day, so the last included day is just before it.
    val endDay = options.window.endMillisExclusive?.let { millisToEpochDay(it - 1) }
    return when {
        startDay != null && endDay != null -> context.localizedChatString(
            R.string.chat_history_export_custom_range,
            formatEpochDay(startDay),
            formatEpochDay(endDay),
        )
        startDay != null -> context.localizedChatString(
            R.string.chat_history_export_range_from,
            formatEpochDay(startDay),
        )
        endDay != null -> context.localizedChatString(
            R.string.chat_history_export_range_until,
            formatEpochDay(endDay),
        )
        else -> context.localizedChatString(R.string.chat_history_export_range_all)
    }
}

/** Resolves group member wxIds to their in-group display names for the exported lines. */
private fun loadGroupSenderNames(groupId: String): Map<String, String> {
    val displayNames = WeDatabaseApi.getGroupMemberDisplayNames(groupId)
    return WeDatabaseApi.getGroupMembers(groupId).associate { contact ->
        contact.wxId to displayNames[contact.wxId].orEmpty()
            .ifEmpty { contact.remarkName }
            .ifEmpty { contact.nickname }
            .ifEmpty { contact.wxId }
    }
}

/**
 * Turns one `message` row into the sender name and body written to the file.
 *
 * Quoted messages (`type 49` app messages with a `refermsg` block) are rendered as the reply text
 * followed by an indented quote line, so the quoted sender and original text are preserved instead
 * of showing up as a generic card label. Group rows carry a `wxid:` prefix that is resolved to the
 * member's display name.
 */
private class MessageBodyFormatter(
    private val context: Context,
    private val isGroupChat: Boolean,
    private val peerDisplayName: String,
    private val senderNames: Map<String, String>,
) {
    private val selfLabel = context.localizedChatString(R.string.chat_history_export_self)
    private val memberLabel = context.localizedChatString(R.string.chat_history_export_group_member)
    private val otherLabel = context.localizedChatString(R.string.chat_history_export_other_message)
    private val quoteWithAuthor = context.localizedChatString(R.string.chat_history_export_quote_line)
    private val quoteAnonymous = context.localizedChatString(R.string.chat_history_export_quote_line_anonymous)

    /** Non-text message types repeat the same handful of labels; resolve each one once. */
    private val typeLabels = HashMap<Int, String>()

    fun format(type: Int, rawContent: String, isSend: Int): RenderedMessage {
        val (prefixedSender, content) = splitSenderPrefix(rawContent, isSend)
        val sender = when {
            isSend == 1 -> selfLabel
            isGroupChat -> prefixedSender?.let { senderNames[it] ?: it } ?: memberLabel
            else -> peerDisplayName
        }

        return RenderedMessage(sender, body(type, content))
    }

    private fun splitSenderPrefix(raw: String, isSend: Int): Pair<String?, String> {
        if (!isGroupChat || isSend == 1) return null to raw
        val colon = raw.indexOf(':')
        if (colon <= 0) return null to raw
        val prefix = raw.substring(0, colon)
        if (!GROUP_SENDER_PREFIX.matches(prefix)) return null to raw
        return prefix to raw.substring(colon + 1).trimStart()
    }

    private fun body(type: Int, content: String): String {
        if (type == MessageType.QUOTE.code) return quoteBody(content)

        if (type == MessageType.TEXT.code) {
            return if (content.startsWith("<msg")) {
                XmlUtils.extractXmlTag(content, "title").ifEmpty { content }
            } else {
                content
            }
        }

        if (type == MessageType.APP.code) {
            val title = XmlUtils.extractXmlTag(content, "title").trim()
            val description = XmlUtils.extractXmlTag(content, "des").trim()
            if (title.isNotEmpty()) return if (description.isNotEmpty()) "$title — $description" else title
            if (description.isNotEmpty()) return description
        }

        return typeLabel(type)
    }

    private fun quoteBody(content: String): String {
        val reference = XmlUtils.extractXmlTag(content, "refermsg")
        val reply = XmlUtils.extractXmlTag(content, "title").trim()
        if (reference.isEmpty()) return reply.ifEmpty { typeLabel(MessageType.QUOTE.code) }

        val author = XmlUtils.extractXmlTag(reference, "displayname").trim()
        val quotedType = XmlUtils.extractXmlTag(reference, "type").trim().toIntOrNull()
        val quoted = describeReferenced(quotedType, XmlUtils.extractXmlTag(reference, "content"))
        val line = if (author.isNotEmpty()) {
            quoteWithAuthor.format(author, quoted)
        } else {
            quoteAnonymous.format(quoted)
        }

        return if (reply.isEmpty()) line else "$reply\n$line"
    }

    /** Text for the referenced (quoted) part; nested app messages expose only their title. */
    private fun describeReferenced(type: Int?, raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return typeLabel(type)

        if (text.startsWith("&lt;msg")) {
            val xml = unescapeXml(text)
            val title = XmlUtils.extractXmlTag(xml, "title").trim()
            if (title.isNotEmpty()) return title
            val description = XmlUtils.extractXmlTag(xml, "des").trim()
            if (description.isNotEmpty()) return description
            return typeLabel(type)
        }

        return when (type) {
            null, MessageType.TEXT.code, MessageType.SYSTEM.code -> text
            else -> if (text.startsWith("<msg")) {
                XmlUtils.extractXmlTag(text, "title").trim().ifEmpty { typeLabel(type) }
            } else {
                typeLabel(type)
            }
        }
    }

    private fun typeLabel(type: Int?): String {
        if (type == null) return otherLabel
        return typeLabels.getOrPut(type) {
            val label = MessageType.fromCode(type)
                ?.let { context.localizedChatString(it.displayNameRes) }
                ?: otherLabel
            "[$label]"
        }
    }

    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
