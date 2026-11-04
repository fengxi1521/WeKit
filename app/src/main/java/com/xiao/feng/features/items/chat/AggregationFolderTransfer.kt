package com.xiao.feng.features.items.chat

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.xiao.feng.R
import com.xiao.feng.activity.TransparentActivity
import com.xiao.feng.data.structured.ConversationFolder
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.android.showToastSuspend
import com.xiao.feng.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Standalone import/export backup for the folders owned by the 归拢 (ConversationAggregation) feature. */
object AggregationFolderTransfer {

    private const val TAG = "AggregationFolderTransfer"
    private const val FILE_NAME = "xfeng_aggregation_folders.json"
    private const val MIME = "application/json"

    @Serializable
    private data class FolderBackup(
        val version: Int = 1,
        val folders: List<ConversationFolder> = emptyList(),
    )

    fun exportBackup(platformContext: Context, localizedContext: Context) {
        val folders = ConversationAggregation.folderBackup()
        if (folders.isEmpty()) {
            showToast(localizedContext, localizedContext.getString(R.string.chat_aggregation_transfer_nothing))
            return
        }
        TransparentActivity.launch(platformContext) {
            val launcher = registerForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val json = DefaultJson.encodeToString(FolderBackup(folders = folders))
                        platformContext.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                            output.write(json.toByteArray())
                        }
                    }
                    result.exceptionOrNull()?.let {
                        WeLogger.e(TAG, "failed to export aggregation folders", it)
                        showToastSuspend(localizedContext.getString(R.string.chat_aggregation_transfer_export_failed))
                    }
                    if (result.isSuccess) {
                        showToastSuspend(
                            localizedContext.getString(
                                R.string.chat_aggregation_transfer_export_success, folders.size
                            )
                        )
                    }
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            launcher.launch(FILE_NAME)
        }
    }

    fun importBackup(platformContext: Context, localizedContext: Context) {
        TransparentActivity.launch(platformContext) {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val json = platformContext.contentResolver.openInputStream(uri)!!.use {
                            it.bufferedReader().readText()
                        }
                        val backup = DefaultJson.decodeFromString<FolderBackup>(json)
                        ConversationAggregation.restoreFolderBackup(backup.folders)
                    }
                    result.exceptionOrNull()?.let {
                        WeLogger.e(TAG, "failed to import aggregation folders", it)
                        showToastSuspend(localizedContext.getString(R.string.chat_aggregation_transfer_import_failed))
                    }
                    result.getOrNull()?.let { (imported, skipped) ->
                        showToastSuspend(
                            localizedContext.getString(
                                R.string.chat_aggregation_transfer_import_success, imported, skipped
                            )
                        )
                    }
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            launcher.launch(arrayOf(MIME, "application/octet-stream", "text/plain"))
        }
    }
}
