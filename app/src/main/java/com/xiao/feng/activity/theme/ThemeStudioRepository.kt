package com.xiao.feng.activity.theme

import android.content.Context
import android.net.Uri
import com.xiao.feng.features.items.beautify.Themes
import org.json.JSONObject
import java.io.File

/**
 * Reads and writes theme folders directly under [Themes.themesDirectory].
 *
 * Every edit is persisted immediately: the folder is the single source of truth that the theme
 * engine reads after WeChat restarts. No separate project/export step is involved.
 */
object ThemeStudioRepository {

    const val NO_THEME_ID = "0"

    data class Draft(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val description: String,
        val colors: Map<String, String>,
        val strings: Map<String, String>,
        val images: Set<String>,
    )

    fun themeDir(id: String): File = File(Themes.themesDirectory(), id)

    fun load(id: String): Draft {
        val dir = themeDir(id)
        val manifestJson = runCatching {
            JSONObject(File(dir, "manifest.json").readText())
        }.getOrElse { JSONObject() }
        val colors = runCatching {
            JSONObject(File(dir, "colors.json").readText())
        }.getOrElse { JSONObject() }
        val strings = runCatching {
            JSONObject(File(dir, "strings.json").readText())
        }.getOrElse { JSONObject() }

        val colorMap = buildMap {
            val keys = colors.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                normalizeColor(colors.opt(key))?.let { put(key, it) }
            }
        }
        val stringMap = buildMap {
            val keys = strings.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                strings.optString(key).takeIf { it.isNotEmpty() }?.let { put(key, it) }
            }
        }
        val images = ThemeStudioData.IMAGE_SLOTS
            .map { it.path }
            .filterTo(mutableSetOf()) { File(dir, it).isFile }

        return Draft(
            id = id,
            name = manifestJson.optString("name", id),
            author = manifestJson.optString("author", ""),
            version = manifestJson.optString("version", "1.0"),
            description = manifestJson.optString("description", ""),
            colors = colorMap,
            strings = stringMap,
            images = images,
        )
    }

    /** Creates a new theme folder and returns its id. The caller can open it in the editor next. */
    fun create(name: String, author: String, version: String, description: String): String {
        val stamp = System.currentTimeMillis()
        var id = "theme_$stamp"
        var suffix = 1
        while (themeDir(id).exists()) {
            id = "theme_${stamp}_${suffix++}"
        }
        val dir = themeDir(id)
        dir.mkdirs()
        writeManifest(id, name, author, version, description)
        return id
    }

    fun writeManifest(id: String, name: String, author: String, version: String, description: String) {
        val dir = themeDir(id)
        if (!dir.isDirectory) return
        val json = JSONObject().apply {
            put("name", name)
            put("author", author)
            put("version", version)
            put("description", description)
        }
        File(dir, "manifest.json").writeText(json.toString(2))
    }

    /**
     * Writes only user-set colors. Black ("000000"/"FF000000") is the picker's "not set" value and
     * must stay absent: the engine falls back to WeChat's native color for missing keys, while a
     * written black would force every themed text black.
     */
    fun writeColors(id: String, colors: Map<String, String>) {
        val dir = themeDir(id)
        if (!dir.isDirectory) return
        val json = JSONObject()
        colors.forEach { (key, value) ->
            val clean = value.trim().removePrefix("#").uppercase()
            if (clean.isNotEmpty() && clean != "000000" && clean != "FF000000") {
                json.put(key, clean)
            }
        }
        File(dir, "colors.json").writeText(json.toString(2))
    }

    /** Writes only non-empty strings; a missing key keeps WeChat's native text. */
    fun writeStrings(id: String, strings: Map<String, String>) {
        val dir = themeDir(id)
        if (!dir.isDirectory) return
        val json = JSONObject()
        strings.forEach { (key, value) ->
            if (value.isNotEmpty()) json.put(key, value)
        }
        File(dir, "strings.json").writeText(json.toString(2))
    }

    /** Copies a picked image into its slot. Returns false when the picker URI cannot be read. */
    fun copyImage(context: Context, id: String, slotPath: String, uri: Uri): Boolean {
        if (slotPath !in ThemeStudioData.IMAGE_SLOT_PATHS) return false
        val dest = File(themeDir(id), slotPath)
        return runCatching {
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    fun removeImage(id: String, slotPath: String) {
        if (slotPath !in ThemeStudioData.IMAGE_SLOT_PATHS) return
        File(themeDir(id), slotPath).delete()
    }

    fun delete(id: String) {
        themeDir(id).deleteRecursively()
        if (Themes.selectedThemeId() == id) Themes.selectTheme(NO_THEME_ID)
    }

    private fun normalizeColor(value: Any?): String? {
        if (value is Number) return "%08X".format(value.toLong() and 0xFFFFFFFFL)
        val raw = value?.toString()?.trim()?.removePrefix("#")?.uppercase().orEmpty()
        if (raw.isEmpty()) return null
        return if (raw.length == 6 || raw.length == 8) raw else null
    }
}
