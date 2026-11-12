package com.xiao.feng.features.items.moments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.xiao.feng.agent.data.entity.ModelEntity
import com.xiao.feng.agent.data.entity.ModelProviderEntity
import com.xiao.feng.agent.data.entity.ModelProviderType
import com.xiao.feng.agent.model.LlmImage
import com.xiao.feng.agent.model.LlmMessage
import com.xiao.feng.agent.model.LlmRole
import com.xiao.feng.agent.model.LlmStreamEvent
import com.xiao.feng.agent.model.ModelProviderManager
import com.xiao.feng.features.api.net.models.protobuf.TimelineObjectProto
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Writes a Moments comment from the post text plus its pictures through DeepSeek's
 * OpenAI-compatible Chat Completions endpoint. `deepseek-flash` accepts images as standard
 * `image_url` content blocks, so the same request also covers text-only posts.
 *
 * Pictures are preferred from the host's local cache; when a post was never opened the image is
 * downloaded straight from the CDN url stored in the post itself, so reading pictures does not
 * depend on WeChat having warmed its cache or on the native media objects being resolvable.
 */
object AutoCommentAi {

    private const val TAG = "AutoCommentAi"
    private const val PROVIDER_ID = "moments_auto_comment"
    private const val MODEL_ROW_ID = "moments_auto_comment_model"
    private const val SESSION_ID = "moments-auto-comment"
    private const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    private const val DEFAULT_MODEL = "deepseek-flash"
    private const val MAX_REPLY_CHARS = 120

    /** Downscale before base64 so a 9-picture post stays well below the request-body limit. */
    private const val MAX_IMAGE_EDGE = 1024
    private const val JPEG_QUALITY = 85

    private const val MEDIA_TYPE_VIDEO = 2
    private const val HTTP_CONNECT_TIMEOUT_MS = 5_000
    private const val HTTP_READ_TIMEOUT_MS = 10_000
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

    /**
     * The shared LLM client allows a 10-minute request — fine for an interactive agent turn, but
     * this runs on the serialized Moments worker, so a stalled endpoint must not hold every later
     * automation item hostage.
     */
    private const val REQUEST_TIMEOUT_MS = 60_000L

    data class Config(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        /** The whole persona prompt: who speaks, personality, tone and reply rules. */
        val persona: String,
        val maxImages: Int,
    )

    /** Returns a ready-to-post comment, or null when the request could not produce one. */
    fun generate(snsInfo: Any?, config: Config): String? {
        if (config.apiKey.isBlank()) {
            WeLogger.w(TAG, "DeepSeek API key is not configured")
            return null
        }
        val text = WeMomentsApi.getContentText(snsInfo).orEmpty()
        val images = runBlocking(Dispatchers.IO) { collectImages(snsInfo, config.maxImages) }
        if (text.isBlank() && images.isEmpty()) {
            WeLogger.i(TAG, "skipping comment: the post has neither text nor readable images")
            return null
        }

        val systemMessage = LlmMessage(
            LlmRole.SYSTEM,
            config.persona.trim().ifBlank { DEFAULT_PERSONA },
        )

        fun messagesFor(attached: List<LlmImage>): List<LlmMessage> {
            val userText = buildString {
                append("朋友圈文字：")
                append(text.trim().ifBlank { "（无文字）" })
                if (attached.isNotEmpty()) {
                    append("\n附图 ")
                    append(attached.size)
                    append(" 张，请结合图片内容评论。")
                }
            }
            return listOf(systemMessage, LlmMessage(LlmRole.USER, userText, images = attached))
        }

        val provider = ModelProviderEntity(
            id = PROVIDER_ID,
            type = ModelProviderType.OPENAI_CHAT_COMPLETION,
            name = "DeepSeek",
            baseUrl = config.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
            apiKey = config.apiKey.trim(),
        )
        val model = ModelEntity(
            id = MODEL_ROW_ID,
            providerId = PROVIDER_ID,
            modelIdRemote = config.model.trim().ifBlank { DEFAULT_MODEL },
            reasoningEffort = null,
            // Comments are short; skip the thinking pass so replies arrive quickly.
            customJsonOverride = """{"thinking":{"type":"disabled"}}""",
            displayName = "DeepSeek",
            maxTokens = 256,
            supportsVision = true,
        )

        return runCatching { ask(provider, model, messagesFor(images)) }
            .recoverCatching { error ->
                // Some endpoints/models reject image content blocks. Rather than falling back to
                // the fixed template, retry with the post text only so the comment is still written
                // from the post itself.
                if (images.isEmpty()) throw error
                WeLogger.w(
                    TAG,
                    "request with ${images.size} image(s) failed, retrying with text only",
                    error,
                )
                ask(provider, model, messagesFor(emptyList()))
            }
            .onFailure { WeLogger.w(TAG, "DeepSeek comment generation failed", it) }
            .getOrNull()
            ?.let(::sanitize)
    }

    private fun ask(
        provider: ModelProviderEntity,
        model: ModelEntity,
        messages: List<LlmMessage>,
    ): String? {
        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = messages,
            tools = emptyList(),
            sessionId = SESSION_ID,
        )
        return runBlocking(Dispatchers.IO) {
            withTimeout(REQUEST_TIMEOUT_MS) {
                var reply: String? = null
                ModelProviderManager.clientFor(provider).stream(request).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Completed -> reply = event.message.content
                        is LlmStreamEvent.Failed -> throw event.error
                        else -> Unit
                    }
                }
                reply
            }
        }
    }

    /** Collects up to [limit] pictures, from the host cache first and the CDN url otherwise. */
    private fun collectImages(snsInfo: Any?, limit: Int): List<LlmImage> {
        if (limit <= 0) return emptyList()
        // Read the media list from the stored proto directly: the native object is only needed for
        // local cache paths, and its resolution can fail for background (not-yet-rendered) posts.
        val mediaList = WeMomentsApi.getTimelineProto(snsInfo)?.contentObj?.mediaList.orEmpty()
        if (mediaList.isEmpty()) {
            WeLogger.i(TAG, "post has no media entries")
            return emptyList()
        }
        val nativeMediaList = runCatching {
            WeMomentsApi.getMomentContent(snsInfo)?.nativeMediaList
        }.onFailure {
            WeLogger.w(TAG, "native media list unavailable, relying on CDN urls", it)
        }.getOrNull()
        WeLogger.i(
            TAG,
            "post media: proto=${mediaList.size}, native=${nativeMediaList?.size ?: -1}",
        )
        val images = mutableListOf<LlmImage>()
        for (index in mediaList.indices) {
            if (images.size >= limit) break
            val media = mediaList[index]
            if (media.type == MEDIA_TYPE_VIDEO) continue

            val cachedPath = runCatching {
                val nativeMedia = nativeMediaList?.getOrNull(index) ?: return@runCatching null
                WeMomentsApi.getCachedImagePath(media, nativeMedia)
            }.onFailure {
                WeLogger.w(TAG, "image $index: cached path lookup failed", it)
            }.getOrNull()

            var bytes = cachedPath?.let(::readCachedBytes)
            var origin = "cache"
            if (bytes == null) {
                bytes = mediaImageUrl(media)?.let(::downloadImage)
                origin = "cdn"
            }
            if (bytes == null) {
                WeLogger.w(TAG, "image $index: no cached file and CDN download failed")
                continue
            }
            val image = encodeImage(bytes)
            if (image == null) {
                WeLogger.w(TAG, "image $index: decode/encode failed (${bytes.size} bytes)")
                continue
            }
            WeLogger.i(TAG, "image $index: using $origin, ${bytes.size} bytes")
            images += image
        }
        WeLogger.i(TAG, "collected ${images.size} image(s) for the comment")
        return images
    }

    /** Cached Moments images may live on WeChat's VFS, so copy them out before reading. */
    private fun readCachedBytes(path: String): ByteArray? {
        val dir = File(HostInfo.application.cacheDir, "auto_comment_ai").apply { mkdirs() }
        val temp = File(dir, "img_${System.nanoTime()}")
        if (!WeMomentsApi.copyExistingFile(path, temp.absolutePath)) return null
        return try {
            temp.takeIf { it.length() > 0L }?.readBytes()
        } finally {
            temp.delete()
        }
    }

    /** The post stores the original picture url; the thumbnail is only a last resort. */
    private fun mediaImageUrl(media: TimelineObjectProto.MediaObjProto): String? =
        listOfNotNull(media.url, media.thumbUrl).firstOrNull { it.startsWith("http") }

    private fun downloadImage(rawUrl: String): ByteArray? {
        // Post urls are often plain http; qpic's CDN serves the same object over https, which is
        // what the host process can actually reach on modern Android.
        val candidates = buildList {
            if (rawUrl.startsWith("http://", ignoreCase = true)) {
                add("https://" + rawUrl.substring("http://".length))
            }
            add(rawUrl)
        }
        for (url in candidates) {
            val bytes = runCatching { fetchBytes(url) }
                .onFailure { WeLogger.w(TAG, "image download failed: $url", it) }
                .getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private fun fetchBytes(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = HTTP_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            if (connection.responseCode !in 200..299) {
                WeLogger.w(TAG, "image download HTTP ${connection.responseCode}: $url")
                return null
            }
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                WeLogger.w(TAG, "image too large (${connection.contentLengthLong} bytes): $url")
                return null
            }
            connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_IMAGE_BYTES) {
                        WeLogger.w(TAG, "image exceeded ${MAX_IMAGE_BYTES} bytes, aborting: $url")
                        return null
                    }
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Downscales and re-encodes as JPEG so a 9-picture post stays far below the body limit. */
    private fun encodeImage(source: ByteArray): LlmImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_IMAGE_EDGE &&
            bounds.outHeight / (sampleSize * 2) >= MAX_IMAGE_EDGE
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        val scaled = scaleDown(decoded, MAX_IMAGE_EDGE)
        return try {
            val jpeg = ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
            LlmImage(Base64.encodeToString(jpeg, Base64.NO_WRAP), "image/jpeg")
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Keeps the first non-empty line, drops wrapping quotes and caps the length. */
    private fun sanitize(raw: String): String? {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        val unquoted = line.trim('"', '\'', '“', '”', '‘', '’', '「', '」', '《', '》')
        return unquoted.take(MAX_REPLY_CHARS).takeIf { it.isNotBlank() }
    }

    /** Persona prompt used when the user leaves the setting blank. */
    const val DEFAULT_PERSONA: String =
        "你是我微信里的一位真实好友，正在刷我的朋友圈。你性格随和、爱开玩笑，说话自然口语化。" +
                "根据这条朋友圈的文字和图片，写一句对应的评论。要求：不超过 30 个字；" +
                "只输出评论本身，不要加引号、不要解释、不要用“评论：”之类的标签，" +
                "也不要复述图片里的全部文字。"
}
