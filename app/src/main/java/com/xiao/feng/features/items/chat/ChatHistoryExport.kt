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
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.xiao.feng.R
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

private enum class ExportRange(val days: Long?, @StringRes val labelRes: Int) {
    ALL(null, R.string.chat_history_export_range_all),
    LAST_30(30, R.string.chat_history_export_range_30),
    LAST_7(7, R.string.chat_history_export_range_7),
}

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
        var range by remember { mutableStateOf(ExportRange.ALL) }
        var displayName by remember(wxId) { mutableStateOf("") }
        var messageCount by remember(wxId) { mutableStateOf<Long?>(null) }
        var exporting by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(wxId) {
            displayName = withContext(Dispatchers.IO) { WeDatabaseApi.getDisplayName(wxId) }
        }
        LaunchedEffect(wxId, range) {
            messageCount = null
            messageCount = withContext(Dispatchers.IO) { countMessages(wxId, range) }
        }

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
                                iconPlaceholder = false,
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
                    }
                    val count = messageCount
                    val statusText = when {
                        exporting -> stringResource(R.string.chat_history_export_in_progress)
                        count == null -> stringResource(R.string.chat_history_export_counting)
                        count == 0L -> stringResource(R.string.chat_history_export_empty)
                        else -> stringResource(R.string.chat_history_export_message_count, count)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    enabled = !exporting && messageCount != null && messageCount != 0L,
                    onClick = {
                        exporting = true
                        result = null
                        scope.launch {
                            val exported = withContext(Dispatchers.IO) {
                                exportChatHistory(context, wxId, displayName, range)
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
                    Text(stringResource(R.string.chat_history_export_button))
                }
            },
            dismissButton = {
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }
}

/** Number of messages within the selected range, for the dialog preview. */
private fun countMessages(wxId: String, range: ExportRange): Long {
    val since = range.days?.let { System.currentTimeMillis() / 1000L - it * 86_400L }
    val sql = buildString {
        append("SELECT COUNT(*) AS msg_count FROM message WHERE talker = ?")
        if (since != null) append(" AND createTime >= ?")
    }
    val args = if (since == null) arrayOf<Any>(wxId) else arrayOf<Any>(wxId, since)
    return WeDatabaseApi.executeQuery(sql, args).firstOrNull()?.let { it["msg_count"] as Long } ?: 0L
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
    range: ExportRange,
): Pair<String, Int>? {
    val since = range.days?.let { System.currentTimeMillis() / 1000L - it * 86_400L }
    val sql = buildString {
        append("SELECT type, content, createTime, isSend FROM message WHERE talker = ?")
        if (since != null) append(" AND createTime >= ?")
        append(" ORDER BY createTime ASC")
    }
    val args = if (since == null) arrayOf<Any>(wxId) else arrayOf<Any>(wxId, since)

    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
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
        target.stream.bufferedWriter().use { writer ->
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
                            timeFormat.format(Date()),
                        )
                    )
                    appendLine(
                        context.localizedChatString(
                            R.string.chat_history_export_header_range,
                            context.localizedChatString(range.labelRes),
                        )
                    )
                    appendLine()
                }
            )

            val selfLabel = context.localizedChatString(R.string.chat_history_export_self)
            WeDatabaseApi.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val time = timeFormat.format(Date(cursor.getLong(2) * 1000L))
                    val sender = if (cursor.getInt(3) == 1) selfLabel else displayName
                    val body = messageBody(context, cursor)
                    writer.write("[$time] $sender: $body\n")
                    count++
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

private fun messageBody(context: Context, cursor: Cursor): String {
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

    val label = messageType?.let { context.localizedChatString(it.displayNameRes) }
        ?: context.localizedChatString(R.string.chat_history_export_other_message)
    return "[$label]"
}
