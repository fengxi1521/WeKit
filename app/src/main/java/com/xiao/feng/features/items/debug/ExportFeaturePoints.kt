package com.xiao.feng.features.items.debug

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import com.xiao.feng.R
import com.xiao.feng.dexkit.cache.DexCacheManager
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToastSuspend
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Dumps the current host version's resolved Dex descriptors so they can be turned into a bundled
 * feature-point table, letting the module skip DexKit scanning entirely.
 */
object ExportFeaturePoints : ClickableFeature() {

    private const val TAG = "ExportFeaturePoints"

    override val technicalId = "导出特征点映射"
    override val nameRes = R.string.feature_export_feature_points_name
    override val categoryIds = listOf(FeatureCategoryIds.DEBUG)
    override val descriptionRes = R.string.feature_export_feature_points_description
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        CoroutineScope(Dispatchers.IO).launch {
            val fileName = "xfeng-feature-points-${HostInfo.versionName}-${HostInfo.versionCode}.json"
            val location = runCatching {
                writeJson(context, fileName, DexCacheManager.exportAll())
            }.getOrElse { error ->
                WeLogger.e(TAG, "failed to export feature points", error)
                showToastSuspend(localizedDebugString(R.string.debug_export_feature_points_failed))
                return@launch
            }
            showToastSuspend(
                localizedDebugString(R.string.debug_export_feature_points_success, location)
            )
        }
    }

    private fun writeJson(context: Context, fileName: String, content: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeJsonToDownloads(context, fileName, content)
        }
        val file = File(checkNotNull(context.getExternalFilesDir(null)), fileName)
        file.writeText(content)
        return file.absolutePath
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeJsonToDownloads(context: Context, fileName: String, content: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        return "Download/$fileName"
    }
}
