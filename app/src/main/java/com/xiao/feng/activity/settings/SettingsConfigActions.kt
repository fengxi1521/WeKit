package com.xiao.feng.activity.settings

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.xiao.feng.R
import com.xiao.feng.activity.TransparentActivity
import com.xiao.feng.data.XiaoFengDatabase
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToastSuspend
import com.xiao.feng.utils.restartHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Shared configuration I/O used by both settings engines. */
object SettingsConfigActions {
    /**
     * Values the module app puts into the [com.xiao.feng.BuildConfig.TAG] extra of the WeChat
     * launcher intent; the host-side injector runs the matching action once WeChat is up.
     */
    const val ACTION_EXPORT_SETTINGS = "xfeng:export_settings"
    const val ACTION_IMPORT_SETTINGS = "xfeng:import_settings"

    /** Exports only feature switches and their saved options as a small JSON document. */
    fun exportSettings(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val exportLauncher = registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val temporary = File(platformContext.cacheDir, ".xfeng-settings-${UUID.randomUUID()}.json")
                    val result = runCatching {
                        SettingsBackup.export(temporary)
                        platformContext.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                            temporary.inputStream().use { it.copyTo(output) }
                        }
                    }
                    result.exceptionOrNull()?.let {
                        showToastSuspend(localizedContext().getString(R.string.config_export_failed))
                        WeLogger.e("SettingsBackup", "failed to export settings", it)
                    }
                    if (result.isSuccess) {
                        showToastSuspend(localizedContext().getString(R.string.config_export_success))
                    }
                    temporary.delete()
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            exportLauncher.launch("xfeng_settings.json")
        }
    }

    /** Restores feature switches and their saved options, then restarts the host to apply them. */
    fun importSettings(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val temporary = File(platformContext.cacheDir, ".xfeng-settings-${UUID.randomUUID()}.json")
                    val result = runCatching {
                        platformContext.contentResolver.openInputStream(uri)!!.use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        }
                        SettingsBackup.import(temporary)
                    }
                    result.exceptionOrNull()?.let {
                        showToastSuspend(localizedContext().getString(R.string.config_import_failed))
                        WeLogger.e("SettingsBackup", "failed to import settings", it)
                    }
                    if (result.isSuccess) {
                        showToastSuspend(localizedContext().getString(R.string.config_import_success))
                    }
                    temporary.delete()
                    withContext(Dispatchers.Main) {
                        finish()
                        if (result.isSuccess) restartHost()
                    }
                }
            }
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    /**
     * Clears every module-owned path under the managed root. Host files and public Downloads are
     * intentionally outside this root and are never touched.
     */
    fun clear() {
        val context = HostInfo.application
        withStorageLock(context) {
            XiaoFengDatabase.close()
            File(context.filesDir, "xfeng").deleteRecursively()
            File(context.filesDir, "xfeng-python").deleteRecursively()
            File(context.filesDir, "xfeng_skip_rewarded_js").deleteRecursively()
            File(context.filesDir, ".xfeng-native").deleteRecursively()
            File(context.codeCacheDir, "generated_proxy_classes").deleteRecursively()
            val mmkv = File(context.filesDir, "mmkv")
            listOf("xfeng_prefs", "xfeng_prefs.crc").forEach { File(mmkv, it).delete() }
            mmkv.listFiles()
                .orEmpty()
                .filter { it.name.startsWith(".xfeng-bootstrap-") }
                .forEach(File::delete)
        }
    }

    fun clearAndRestart() {
        clear()
        restartHost()
    }

    private fun <T> withStorageLock(context: Context, block: () -> T): T {
        val file = File(context.filesDir, ".xfeng-storage-operation.lock")
        return FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }
}
