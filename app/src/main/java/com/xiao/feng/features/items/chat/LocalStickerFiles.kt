package com.xiao.feng.features.items.chat

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import java.io.File

/**
 * 本机表情文件，和「定时消息」的 EMOJI 内容走同一套发送方式。
 *
 * 用户在相册/文件里挑一张图片（GIF 也可以），模块把它复制进自己的目录并保存绝对路径；
 * 发送时交给 [WeMessageApi.sendEmoji]，由微信把它当成自定义表情发出去。
 */
object LocalStickerFiles {

    private const val TAG = "LocalStickerFiles"

    fun send(toUser: String, path: String): Boolean = WeMessageApi.sendEmoji(toUser, path)

    /** 把挑选到的文件复制进模块目录, 返回可以长期使用的绝对路径; 失败返回 null。 */
    fun importFile(activity: FragmentActivity, uri: Uri, name: String): String? = runCatching {
        val dir = File(HostInfo.application.filesDir, "local_stickers").apply { mkdirs() }
        val safeName = name.substringAfterLast('/').ifBlank { "sticker" }
        val destination = File(dir, "${System.currentTimeMillis()}_$safeName")
        val input = activity.contentResolver.openInputStream(uri)
            ?: error("cannot open picked sticker: $uri")
        input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        destination.absolutePath
    }.onFailure {
        WeLogger.w(TAG, "failed to import sticker file", it)
    }.getOrNull()
}
