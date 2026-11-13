package com.xiao.feng.features.items.chat.ai

import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.fs.KnownPaths
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

/**
 * 读取并发送用户自己的微信自定义表情包（EmojiInfo 中 catalog=81 的收藏表情）。
 */
object AiChatStickers {

    private const val TAG = "AiChatStickers"

    /** 微信自定义表情的 catalog。 */
    private const val CUSTOM_STICKER_CATALOG = 81

    /** 读取用户收藏的全部自定义表情 md5，最近使用的排在前面。 */
    fun listMd5s(): List<String> = runCatching {
        WeDatabaseApi.db.rawQuery(
            "SELECT md5 FROM EmojiInfo WHERE catalog = $CUSTOM_STICKER_CATALOG AND temp = 0 ORDER BY reserved3 DESC",
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
    }.onFailure {
        WeLogger.w(TAG, "failed to list custom stickers", it)
    }.getOrDefault(emptyList())

    /** 某个 md5 对应的表情文件（本地未缓存时返回 null，跳过发送即可）。 */
    fun stickerFile(md5: String) = KnownPaths.moduleCache / "ai_chat_sticker_$md5.gif"

    /** 解码并发送一个表情包。 */
    fun send(toUser: String, md5: String): Boolean {
        val destination = stickerFile(md5)
        val file = WeMessageApi.decodeStickerToFile(md5, destination) ?: return false
        return WeMessageApi.sendSticker(toUser, file.absolutePathString())
    }
}
