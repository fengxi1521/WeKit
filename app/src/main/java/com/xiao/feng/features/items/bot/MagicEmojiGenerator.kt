package com.xiao.feng.features.items.bot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.fs.KnownPaths
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

/**
 * 魔法表情：下载引用消息发送者的头像，在本地合成带头像的动画 GIF 并作为表情发送。
 *
 * 原 Hchat 插件依赖未随包提供的快照与外部接口；这里改为完全本地生成，25 个指令映射到
 * 不同的动画模板，离线也能使用。
 */
object MagicEmojiGenerator {

    private const val TAG = "MagicEmoji"
    private const val SIZE = 144
    private const val FRAMES = 12
    private const val DELAY_CS = 8
    private const val AVATAR_SIZE = 88f

    private enum class Motion { PAT, SHAKE, FLY, ZOOM, SPIN, TILT, RAGE }

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 32f
        color = Color.WHITE
    }

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 22f
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * 异步生成并发送。返回 false 表示参数无效；下载或合成失败只会记录日志。
     */
    fun send(toUser: String, wxId: String, motionName: String, caption: String): Boolean {
        if (toUser.isEmpty() || wxId.isEmpty()) return false
        val motion = runCatching { Motion.valueOf(motionName) }.getOrDefault(Motion.SHAKE)
        thread(name = "MagicEmoji") {
            runCatching {
                val avatar = loadAvatar(wxId)
                val target = File(
                    KnownPaths.moduleCache.toFile(),
                    "magic-emoji-${System.currentTimeMillis()}-${abs(caption.hashCode())}.gif",
                )
                try {
                    target.outputStream().use { out ->
                        GifWriter(out, SIZE, SIZE).use { writer ->
                            repeat(FRAMES) { frame ->
                                val bitmap = renderFrame(avatar, motion, caption, frame)
                                writer.addFrame(bitmap, DELAY_CS)
                                bitmap.recycle()
                            }
                        }
                    }
                    check(WeMessageApi.sendSticker(toUser, target.absolutePath)) {
                        "sendSticker failed"
                    }
                } finally {
                    avatar.recycle()
                    target.delete()
                }
            }.onFailure {
                WeLogger.e(TAG, "failed to send magic emoji '$caption'", it)
                runCatching { WeMessageApi.sendText(toUser, "表情生成失败了，请稍后再试") }
            }
        }
        return true
    }

    private fun loadAvatar(wxId: String): Bitmap {
        val url = WeDatabaseApi.getAvatarUrl(wxId)
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            check(response.isSuccessful) { "avatar request failed: ${response.code}" }
            response.body.bytes()
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("avatar decode failed")
    }

    private fun renderFrame(source: Bitmap, motion: Motion, caption: String, frame: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor(motion))

        val t = frame / FRAMES.toFloat()
        val angle = t * PI * 2
        var dx = 0f
        var dy = 0f
        var rotation = 0f
        var scale = 1f

        when (motion) {
            Motion.PAT -> dy = sin(angle).toFloat() * 6f
            Motion.SHAKE -> dx = sin(angle * 3).toFloat() * 5f
            Motion.RAGE -> {
                dx = sin(angle * 4).toFloat() * 4f
                scale = 1.08f
            }
            Motion.FLY -> {
                dx = sin(angle).toFloat() * 20f
                dy = -abs(sin(angle)).toFloat() * 8f
                rotation = sin(angle).toFloat() * 12f
            }
            Motion.ZOOM -> scale = 1f + 0.12f * sin(angle).toFloat()
            Motion.SPIN -> rotation = sin(angle).toFloat() * 18f
            Motion.TILT -> rotation = -10f + sin(angle).toFloat() * 6f
        }

        val avatar = circularAvatar(source, AVATAR_SIZE.toInt())
        canvas.save()
        canvas.translate(SIZE / 2f + dx, SIZE * 0.52f + dy)
        canvas.rotate(rotation)
        canvas.scale(scale, scale)
        canvas.drawBitmap(avatar, -AVATAR_SIZE / 2f, -AVATAR_SIZE / 2f, null)
        canvas.restore()
        avatar.recycle()

        canvas.drawText(emojiFor(motion), SIZE / 2f, 34f, emojiPaint)
        canvas.drawText(caption, SIZE / 2f, SIZE - 12f, captionPaint)
        return bitmap
    }

    private fun backgroundColor(motion: Motion): Int = when (motion) {
        Motion.PAT -> Color.rgb(32, 44, 66)
        Motion.SHAKE -> Color.rgb(42, 42, 50)
        Motion.RAGE -> Color.rgb(96, 24, 24)
        Motion.FLY -> Color.rgb(22, 52, 86)
        Motion.ZOOM -> Color.rgb(72, 42, 92)
        Motion.SPIN -> Color.rgb(22, 62, 62)
        Motion.TILT -> Color.rgb(66, 54, 22)
    }

    private fun emojiFor(motion: Motion): String = when (motion) {
        Motion.PAT -> "🖐"
        Motion.SHAKE -> "💥"
        Motion.RAGE -> "😡"
        Motion.FLY -> "✈️"
        Motion.ZOOM -> "👑"
        Motion.SPIN -> "🌀"
        Motion.TILT -> "🐟"
    }

    private fun circularAvatar(source: Bitmap, size: Int): Bitmap {
        val square = centerCrop(source, size)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        if (square !== source) square.recycle()
        return output
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val cropped = Bitmap.createBitmap(source, left, top, side, side)
        val scaled = if (cropped.width == size && cropped.height == size) {
            cropped
        } else {
            Bitmap.createScaledBitmap(cropped, size, size, true)
        }
        if (scaled !== cropped && cropped !== source) cropped.recycle()
        return scaled
    }

    /**
     * 最小可用的 GIF89a 动画编码器：固定 6x6x6 调色板 + 标准 LZW 压缩。
     */
    private class GifWriter(
        private val out: OutputStream,
        private val width: Int,
        private val height: Int,
    ) : Closeable {

        private val palette = ByteArray(256 * 3)

        init {
            var index = 0
            for (r in 0..5) {
                for (g in 0..5) {
                    for (b in 0..5) {
                        palette[index * 3] = (r * 51).toByte()
                        palette[index * 3 + 1] = (g * 51).toByte()
                        palette[index * 3 + 2] = (b * 51).toByte()
                        index++
                    }
                }
            }
            while (index < 256) {
                val value = ((index - 216) * 255 / 39).toByte()
                palette[index * 3] = value
                palette[index * 3 + 1] = value
                palette[index * 3 + 2] = value
                index++
            }
            writeHeader()
        }

        fun addFrame(bitmap: Bitmap, delayCs: Int) {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val indices = ByteArray(width * height)
            for (i in pixels.indices) {
                val color = pixels[i]
                val r = ((color shr 16) and 0xFF) * 5 / 255
                val g = ((color shr 8) and 0xFF) * 5 / 255
                val b = (color and 0xFF) * 5 / 255
                indices[i] = (r * 36 + g * 6 + b).toByte()
            }

            out.write(0x21)
            out.write(0xF9)
            out.write(0x04)
            out.write(0x04)
            writeShort(delayCs)
            out.write(0x00)
            out.write(0x00)

            out.write(0x2C)
            writeShort(0)
            writeShort(0)
            writeShort(width)
            writeShort(height)
            out.write(0x00)

            writeLzw(indices)
        }

        override fun close() {
            out.write(0x3B)
            out.flush()
        }

        private fun writeHeader() {
            out.write("GIF89a".toByteArray(Charsets.US_ASCII))
            writeShort(width)
            writeShort(height)
            out.write(0xF7)
            out.write(0x00)
            out.write(0x00)
            out.write(palette)
            out.write(0x21)
            out.write(0xFF)
            out.write(0x0B)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(0x03)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00)
            out.write(0x00)
        }

        private fun writeShort(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }

        private fun writeLzw(indices: ByteArray) {
            val minCodeSize = 8
            out.write(minCodeSize)

            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var nextCode = endCode + 1
            val dictionary = HashMap<Int, Int>(8192)
            val data = ByteArrayOutputStream()
            var bitBuffer = 0
            var bitCount = 0

            fun emit(code: Int) {
                bitBuffer = bitBuffer or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    data.write(bitBuffer and 0xFF)
                    bitBuffer = bitBuffer ushr 8
                    bitCount -= 8
                }
            }

            fun resetDictionary() {
                dictionary.clear()
                codeSize = minCodeSize + 1
                nextCode = endCode + 1
            }

            emit(clearCode)
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val next = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or next
                val existing = dictionary[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emit(prefix)
                    if (nextCode < 4096) {
                        dictionary[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                    } else {
                        emit(clearCode)
                        resetDictionary()
                    }
                    prefix = next
                }
            }
            emit(prefix)
            emit(endCode)
            if (bitCount > 0) data.write(bitBuffer and 0xFF)

            val bytes = data.toByteArray()
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(255, bytes.size - offset)
                out.write(length)
                out.write(bytes, offset, length)
                offset += length
            }
            out.write(0x00)
        }
    }
}
