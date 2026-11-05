package com.xiao.feng.featurepoints

import android.content.res.AssetManager
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.fs.KnownPaths
import java.io.FileNotFoundException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Descriptors for the host version, bundled as an asset and refreshed only by shipping a new
 * table. Keys are `technicalId` plus the feature's delegate property name.
 */
object FeaturePointsTable {
    private const val TAG = "FeaturePointsTable"
    private const val DIR = "feature_points"

    private val json = Json { ignoreUnknownKeys = true }
    private var entries: Map<String, Map<String, String>>? = null

    fun load(assets: AssetManager): Boolean {
        if (entries != null) return true

        val table = read(assets) ?: return false
        if (table.versionCode != HostInfo.versionCode || table.isGooglePlay != HostInfo.isHostGooglePlay) {
            WeLogger.e(
                TAG,
                "bundled feature points do not match the current host: " +
                        "${HostInfo.versionName}-${HostInfo.versionCode} play=${HostInfo.isHostGooglePlay}",
            )
            return false
        }
        entries = table.features.associate { it.technicalId to it.descriptors }
        WeLogger.i(TAG, "loaded ${entries!!.size} bundled feature entries")
        return true
    }

    fun descriptorsFor(technicalId: String): Map<String, String>? = entries?.get(technicalId)

    private fun read(assets: AssetManager): FeaturePointsExport? = try {
        val text = assets.open("$DIR/${KnownPaths.hostVersionKey()}.json")
            .bufferedReader()
            .use { it.readText() }
        json.decodeFromString<FeaturePointsExport>(text)
    } catch (_: FileNotFoundException) {
        WeLogger.e(TAG, "no bundled feature points for ${KnownPaths.hostVersionKey()}")
        null
    } catch (e: Exception) {
        WeLogger.e(TAG, "failed to read bundled feature points", e)
        null
    }
}

@Serializable
private data class FeaturePointsExport(
    val versionName: String,
    val versionCode: Long,
    val isGooglePlay: Boolean,
    val features: List<ExportedFeature>,
)

@Serializable
private data class ExportedFeature(
    val technicalId: String,
    val descriptors: Map<String, String>,
)
