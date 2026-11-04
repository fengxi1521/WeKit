package com.xiao.feng.activity.settings

import android.content.Context
import android.provider.Settings
import com.xiao.feng.data.KvStore
import com.xiao.feng.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Card-key licensing for the WIG panel (appid 10992).
 *
 * `POST /api/login/{time}/{appid}/{card}` with `imei=<device id>` returns a hex-encoded RC4
 * ciphertext whose plaintext is a JSON envelope. `code == "20000"` means the card is valid and
 * carries the heartbeat token; every other code is a rejection with a human-readable `msg`.
 */
object ModuleLicense {

    private const val TAG = "ModuleLicense"
    private const val HOST = "https://www.wigwy.xyz"
    private const val APP_ID = "10992"
    private const val RC4_KEY = "cTsD9rzUvKylbxS"

    private const val PREF_CARD = "module_license_card"
    private const val PREF_TOKEN = "module_license_token"
    private const val PREF_EXPIRES = "module_license_expires"

    data class LicenseInfo(val token: String, val expiresAt: Long, val cardType: String, val message: String)

    fun savedCard(): String = KvStore.getStringOrDef(PREF_CARD, "")

    fun savedExpiresAt(): Long =
        KvStore.getStringOrDef(PREF_EXPIRES, "0").toLongOrNull() ?: 0L

    /** A stored token is valid until its server expiry; unknown expiry is treated as valid. */
    val isVerified: Boolean
        get() {
            val token = KvStore.getStringOrDef(PREF_TOKEN, "")
            return token.isNotBlank() &&
                (savedExpiresAt() == 0L || savedExpiresAt() > System.currentTimeMillis() / 1000)
        }

    suspend fun verify(context: Context, card: String): Result<LicenseInfo> = withContext(Dispatchers.IO) {
        try {
            Result.success(login(context, card.trim()))
        } catch (error: Exception) {
            WeLogger.w(TAG, "card verification failed", error)
            Result.failure(error)
        }
    }

    private fun login(context: Context, card: String): LicenseInfo {
        val time = System.currentTimeMillis() / 1000
        val url = URL("$HOST/api/login/$time/$APP_ID/${URLEncoder.encode(card, "UTF-8")}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        connection.outputStream.use { output ->
            output.write("imei=${URLEncoder.encode(deviceId(context), "UTF-8")}".toByteArray())
        }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
            .trim()
        if (body.isEmpty()) error("HTTP $status")
        val jsonText = if (body.startsWith("{")) body else rc4Decrypt(hexDecode(body))
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val code = root["code"]?.jsonPrimitive?.content.orEmpty()
        if (code != "20000") {
            error(root["msg"]?.jsonPrimitive?.content ?: "验证失败 ($code)")
        }
        val token = root["token"]?.jsonPrimitive?.content.orEmpty()
        if (token.isBlank()) error(root["msg"]?.jsonPrimitive?.content ?: "验证失败")
        val expiresAt = root["expires_ts"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val cardType = root["card_type"]?.jsonPrimitive?.content.orEmpty()
        val message = root["msg"]?.jsonPrimitive?.content.orEmpty()
        KvStore.putString(PREF_CARD, card)
        KvStore.putString(PREF_TOKEN, token)
        KvStore.putString(PREF_EXPIRES, expiresAt.toString())
        return LicenseInfo(token, expiresAt, cardType, message)
    }

    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun hexDecode(value: String): ByteArray {
        val clean = value.trim()
        require(clean.length % 2 == 0) { "invalid response" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun rc4Decrypt(cipher: ByteArray): String {
        val key = RC4_KEY.toByteArray(Charsets.UTF_8)
        val state = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + state[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
        }
        var i = 0
        j = 0
        val plain = ByteArray(cipher.size)
        for (index in cipher.indices) {
            i = (i + 1) and 0xff
            j = (j + state[i]) and 0xff
            val swap = state[i]
            state[i] = state[j]
            state[j] = swap
            plain[index] = (cipher[index].toInt() xor state[(state[i] + state[j]) and 0xff]).toByte()
        }
        return plain.toString(Charsets.UTF_8)
    }
}
