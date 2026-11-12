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
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Writes a Moments comment from the post text plus its cached pictures through DeepSeek's
 * OpenAI-compatible Chat Completions endpoint. `deepseek-flash` accepts images as standard
 * `image_url` content blocks, so the same request also covers text-only posts.
 *
 * Image resolution runs on the main thread (the host's download trigger expects it), while decoding
 * and the LLM request run on worker threads.
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

    /**
     * A post is commented the moment the host stores it, before anyone has opened the picture, so
     * the big image is usually not cached yet. Ask the host to fetch it (the same path as tapping
     * to view it) and wait at most this long — a slow CDN link then falls back to a text-only reply.
     */
    private const val IMAGE_FETCH_TIMEOUT_MS = 15_000L

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
        // Resolve/trigger image downloads on the main thread like the host's own "view large image"
        // flow, then decode the files off the main thread.
        val images = runBlocking {
            val paths = withContext(Dispatchers.Main.immediate) {
                resolveImagePaths(snsInfo, config.maxImages)
            }
            withContext(Dispatchers.IO) { paths.mapNotNull(::encodeImage) }
        }
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

    /** Resolves up to [limit] cached image paths, asking the host to download any missing picture. */
    private suspend fun resolveImagePaths(snsInfo: Any?, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val content = WeMomentsApi.getMomentContent(snsInfo)
        if (content == null) {
            WeLogger.w(TAG, "no MomentContent for this post, images skipped")
            return emptyList()
        }
        WeLogger.i(
            TAG,
            "post media: proto=${content.mediaList.size}, native=${content.nativeMediaList.size}",
        )
        val paths = mutableListOf<String>()
        for (index in content.mediaList.indices) {
            if (paths.size >= limit) break
            val media = content.mediaList[index]
            val nativeMedia = content.nativeMediaList.getOrNull(index)
            if (nativeMedia == null) {
                WeLogger.w(TAG, "image $index: no native media object")
                continue
            }
            val path = WeMomentsApi.getCachedImagePath(media, nativeMedia)
                ?: WeMomentsApi.ensureImageCached(media, nativeMedia, IMAGE_FETCH_TIMEOUT_MS)
            if (path == null) {
                WeLogger.w(TAG, "image $index: no cached path and download did not finish")
                continue
            }
            paths += path
        }
        WeLogger.i(TAG, "resolved ${paths.size} image path(s) for the comment")
        return paths
    }

    /**
     * Cached Moments images may live on WeChat's VFS, so copy them out through the API helper
     * before reading, then downscale and re-encode as JPEG.
     */
    private fun encodeImage(path: String): LlmImage? {
        val dir = File(HostInfo.application.cacheDir, "auto_comment_ai").apply { mkdirs() }
        val temp = File(dir, "img_${System.nanoTime()}")
        if (!WeMomentsApi.copyExistingFile(path, temp.absolutePath)) return null
        return try {
            val jpeg = compressJpeg(temp) ?: return null
            LlmImage(Base64.encodeToString(jpeg, Base64.NO_WRAP), "image/jpeg")
        } finally {
            temp.delete()
        }
    }

    private fun compressJpeg(file: File): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_IMAGE_EDGE &&
            bounds.outHeight / (sampleSize * 2) >= MAX_IMAGE_EDGE
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val scaled = scaleDown(decoded, MAX_IMAGE_EDGE)
        return try {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }
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
