package com.xiao.feng.activity.settings

import com.xiao.feng.data.XiaoFengDatabase
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.restartHost
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Shared configuration actions used by both settings engines. */
object SettingsConfigActions {
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

    private fun <T> withStorageLock(context: android.content.Context, block: () -> T): T {
        val file = File(context.filesDir, ".xfeng-storage-operation.lock")
        return FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }
}
