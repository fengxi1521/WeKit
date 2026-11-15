package com.xiao.feng.activity.settings

import android.database.Cursor
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xiao.feng.BuildConfig
import com.xiao.feng.data.XiaoFengDatabase
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.serialization.DefaultJson
import java.io.File
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Portable backup of every feature switch and saved option.
 *
 * Unlike [BackupCoordinator] (a full database/file snapshot that requires a matching schema), this
 * only carries settings: the preference store, exportable documents and the structured config
 * tables (automation rules, aggregation folders, AI providers/models/prompts, ...). It is small,
 * readable JSON so it can be moved between devices and module versions.
 */
object SettingsBackup {
    private const val TAG = "SettingsBackup"
    private const val FORMAT = "xfeng-settings"
    private const val VERSION = 1
    private const val NAMESPACE = "xfeng_prefs"
    private const val PAYMENT_KEY = "payment_pswd_encdata"
    private const val BLOB_MARKER = "\$base64"

    /**
     * Config tables copied as-is. Parents come before their children (insert order); deletion
     * runs in reverse so foreign keys never dangle.
     */
    private val configTables = listOf(
        "red_packet_rules",
        "transfer_rules",
        "moment_automation_rules",
        "conversation_groups",
        "conversation_folders",
        "model_providers",
        "models",
        "system_prompts",
        "per_turn_prompts",
        "conditional_prompts",
        "preset_prompts",
        "settings",
        "external_services",
        "linux_environments",
        "providers",
        "feature_flag_overrides",
        "moment_custom_details",
        "contact_real_name_parts",
        "real_name_scan_progress",
        "red_packet_keywords",
        "transfer_keywords",
        "moment_automation_keywords",
        "moment_automation_content_types",
        "conversation_group_members",
        "conversation_folder_members",
    )

    fun export(output: File) {
        val db = XiaoFengDatabase.instance.openHelper.readableDatabase
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("moduleVersion", BuildConfig.VERSION_NAME)
            put("exportedAt", System.currentTimeMillis())
            put("preferences", exportPreferences(db))
            put("documents", exportDocuments(db))
            put("tables", exportTables(db))
        }
        output.parentFile?.mkdirs()
        output.toPath().writeText(DefaultJson.encodeToString(root), Charsets.UTF_8)
    }

    fun import(input: File) {
        val root = Json.parseToJsonElement(input.toPath().readText()).jsonObject
        check(root["format"]?.jsonPrimitive?.content == FORMAT) { "不是 XiaoFeng 功能配置备份" }
        check(root["version"]?.jsonPrimitive?.int == VERSION) { "不支持的功能配置备份版本" }

        val db = XiaoFengDatabase.instance.openHelper.writableDatabase
        db.beginTransaction()
        try {
            restorePreferences(db, root["preferences"]?.jsonArray.orEmpty())
            restoreDocuments(db, root["documents"]?.jsonArray.orEmpty())
            restoreTables(db, root["tables"] as? JsonObject ?: JsonObject(emptyMap()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ── Export ───────────────────────────────────────────────────────────────

    private fun exportPreferences(db: SupportSQLiteDatabase): JsonArray = buildJsonArray {
        db.query(
            "SELECT `key`, valueType, valueText, valueLong, valueDouble, valueBlob " +
                    "FROM preference_entries WHERE namespace = ? AND exportable = 1",
            arrayOf(NAMESPACE),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                if (key.contains(PAYMENT_KEY)) continue
                val type = cursor.getString(1)
                if (type !in SUPPORTED_TYPES) continue
                add(buildJsonObject {
                    put("key", key)
                    put("type", type)
                    when (type) {
                        "bool" -> put("value", cursor.getLong(3) != 0L)
                        "int", "long" -> put("value", cursor.getLongOrNull(3))
                        "float" -> put("value", cursor.getDoubleOrNull(4))
                        "string" -> put("value", cursor.getStringOrNull(2))
                        "bytes" -> put("value", cursor.getBlobOrNull(5)?.let(::encodeBlob))
                        "string_set" -> put("value", exportSetMembers(db, key))
                    }
                })
            }
        }
    }

    private fun exportSetMembers(db: SupportSQLiteDatabase, key: String): JsonArray = buildJsonArray {
        db.query(
            "SELECT member FROM preference_set_members WHERE namespace = ? AND `key` = ? ORDER BY member",
            arrayOf(NAMESPACE, key),
        ).use { cursor ->
            while (cursor.moveToNext()) add(JsonPrimitive(cursor.getString(0)))
        }
    }

    private fun exportDocuments(db: SupportSQLiteDatabase): JsonArray = buildJsonArray {
        db.query(
            "SELECT namespace, `key`, content, formatVersion FROM documents WHERE exportable = 1",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(buildJsonObject {
                    put("namespace", cursor.getString(0))
                    put("key", cursor.getString(1))
                    put("content", cursor.getString(2))
                    put("formatVersion", cursor.getInt(3))
                })
            }
        }
    }

    private fun exportTables(db: SupportSQLiteDatabase): JsonObject = buildJsonObject {
        for (name in configTables) {
            if (!tableExists(db, name)) continue
            db.query("SELECT * FROM `$name`", null).use { cursor ->
                val columns = cursor.columnNames
                val rows = buildJsonArray {
                    while (cursor.moveToNext()) {
                        add(buildJsonArray { columns.indices.forEach { add(cursor.jsonValue(it)) } })
                    }
                }
                put(name, buildJsonObject {
                    put("columns", buildJsonArray { columns.forEach { add(JsonPrimitive(it)) } })
                    put("rows", rows)
                })
            }
        }
    }

    // ── Import ───────────────────────────────────────────────────────────────

    private fun restorePreferences(db: SupportSQLiteDatabase, items: List<JsonElement>) {
        for (item in items) {
            val obj = item.jsonObject
            val key = obj["key"]!!.jsonPrimitive.content
            if (key.contains(PAYMENT_KEY)) continue
            val type = obj["type"]!!.jsonPrimitive.content
            if (type !in SUPPORTED_TYPES) continue
            val value = obj["value"] ?: JsonNull
            when (type) {
                "bool" -> putPreference(db, key, type, long = if (value.jsonPrimitive.boolean) 1L else 0L)
                "int", "long" -> putPreference(db, key, type, long = value.jsonPrimitive.long)
                "float" -> putPreference(db, key, type, double = value.jsonPrimitive.double)
                "string" -> putPreference(db, key, type, text = value.jsonPrimitive.content)
                "bytes" -> putPreference(db, key, type, blob = decodeBlob(value.jsonPrimitive.content))
                "string_set" -> {
                    putPreference(db, key, type)
                    db.execSQL(
                        "DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?",
                        arrayOf(NAMESPACE, key),
                    )
                    value.jsonArray.forEach { member ->
                        db.execSQL(
                            "INSERT OR REPLACE INTO preference_set_members(namespace, `key`, member) VALUES (?, ?, ?)",
                            arrayOf(NAMESPACE, key, member.jsonPrimitive.content),
                        )
                    }
                }
            }
        }
    }

    private fun putPreference(
        db: SupportSQLiteDatabase,
        key: String,
        type: String,
        text: String? = null,
        long: Long? = null,
        double: Double? = null,
        blob: ByteArray? = null,
    ) {
        db.execSQL(
            "INSERT OR REPLACE INTO preference_entries(namespace, `key`, valueType, valueText, valueLong, valueDouble, valueBlob, encodingVersion, revision, updatedAt, exportable) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 1, COALESCE((SELECT revision + 1 FROM preference_entries WHERE namespace = ? AND `key` = ?), 1), ?, 1)",
            arrayOf(NAMESPACE, key, type, text, long, double, blob, NAMESPACE, key, System.currentTimeMillis()),
        )
    }

    private fun restoreDocuments(db: SupportSQLiteDatabase, items: List<JsonElement>) {
        for (item in items) {
            val obj = item.jsonObject
            db.execSQL(
                "INSERT OR REPLACE INTO documents(namespace, `key`, content, formatVersion, updatedAt, exportable) VALUES (?, ?, ?, ?, ?, 1)",
                arrayOf(
                    obj["namespace"]!!.jsonPrimitive.content,
                    obj["key"]!!.jsonPrimitive.content,
                    obj["content"]!!.jsonPrimitive.content,
                    obj["formatVersion"]!!.jsonPrimitive.int,
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun restoreTables(db: SupportSQLiteDatabase, tables: JsonObject) {
        // Drop children first, then parents.
        for (name in configTables.asReversed()) {
            if (tables[name] == null || !tableExists(db, name)) continue
            db.execSQL("DELETE FROM `$name`")
        }
        for (name in configTables) {
            val table = tables[name]?.jsonObject ?: continue
            if (!tableExists(db, name)) {
                WeLogger.w(TAG, "settings backup contains unknown table $name; skipped")
                continue
            }
            val existing = tableColumns(db, name)
            val source = table["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
            val included = source.withIndex().filter { it.value in existing }
            if (included.isEmpty()) continue
            val columnList = included.joinToString(",") { "`${it.value}`" }
            val placeholders = included.joinToString(",") { "?" }
            for (row in table["rows"]!!.jsonArray) {
                val values = included.map { (index, _) -> row.jsonArray[index].toBindValue() }
                db.execSQL(
                    "INSERT OR REPLACE INTO `$name`($columnList) VALUES ($placeholders)",
                    values.toTypedArray(),
                )
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun tableExists(db: SupportSQLiteDatabase, name: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

    private fun tableColumns(db: SupportSQLiteDatabase, name: String): Set<String> =
        db.query("PRAGMA table_info(`$name`)", null).use { cursor ->
            buildSet {
                val index = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(index))
            }
        }

    private fun Cursor.jsonValue(index: Int): JsonElement = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JsonNull
        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> JsonPrimitive(getDouble(index))
        Cursor.FIELD_TYPE_STRING -> JsonPrimitive(getString(index))
        Cursor.FIELD_TYPE_BLOB -> buildJsonObject { put(BLOB_MARKER, encodeBlob(getBlob(index))) }
        else -> JsonNull
    }

    private fun JsonElement.toBindValue(): Any? {
        if (this is JsonNull) return null
        val obj = this as? JsonObject
        if (obj != null) {
            val blob = obj[BLOB_MARKER] ?: return null
            return decodeBlob(blob.jsonPrimitive.content)
        }
        val primitive = jsonPrimitive
        if (primitive.isString) return primitive.content
        primitive.asLongOrNull?.let { return it }
        return primitive.double
    }

    private fun encodeBlob(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBlob(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private val SUPPORTED_TYPES = setOf("bool", "int", "long", "float", "string", "string_set", "bytes")

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
    private fun Cursor.getBlobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
}

private val JsonPrimitive.asLongOrNull: Long?
    get() = runCatching { long }.getOrNull()
