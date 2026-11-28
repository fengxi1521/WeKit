@file:Suppress("NOTHING_TO_INLINE", "unused")

package com.xiao.feng.features.api.core.models

import android.content.ContentValues
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.utils.reflection.cachedField
import com.xiao.feng.utils.serialization.NativeXmlParser
import com.xiao.feng.utils.serialization.XmlArray
import com.xiao.feng.utils.serialization.XmlObject
import com.xiao.feng.utils.serialization.XmlUtils
import com.xiao.feng.utils.serialization.asInt
import com.xiao.feng.utils.serialization.asLong
import com.xiao.feng.utils.serialization.asString
import com.xiao.feng.utils.serialization.get
import com.xiao.feng.utils.serialization.getByPath
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import java.nio.ByteBuffer

class MessageInfo(val instance: Any) {

    val typeCode by lazy { getFieldByName<Int>(instance, "field_type") }
    val type by lazy { MessageType.fromCode(typeCode) }

    val id by lazy { getFieldByName<Long>(instance, "field_msgId") }
    val serverId by lazy { getFieldByName<Long>(instance, "field_msgSvrId") }
    val isSend by lazy { getFieldByName<Int>(instance, "field_isSend") }
    val createTime by lazy { getFieldByName<Long>(instance, "field_createTime") }
    // field_talker / field_content 均由 `cursor.getString()` 或 `contentValues.getAsString()` 填充,
    // 微信在缺失时会留 null, 故此处降级为空串而非抛 NPE
    val talker by lazy { getFieldByName<String?>(instance, "field_talker").orEmpty() }
    val content by lazy { getFieldByName<String?>(instance, "field_content").orEmpty() }

    val actualContent: String
        get() {
            var text = content
            if (isInGroupChat) {
                text = text.stripWxId()
            }
            return text
        }

    val quoteMsgActualContent: String?
        get() {
            val quoteMsg = toQuoteMessage() ?: return null

            var text = quoteMsg.title
            if (isInGroupChat) {
                text = text.stripWxId()
            }

            return text
        }

    val humanReadableRepr: String
        get() {
            val type = type ?: return "[${MessageType.UNKNOWN.displayName}]"

            return when {
                type.code == MessageType.QUOTE.code -> quoteMsgActualContent ?: actualContent
                type.isText -> actualContent
                type.isSystem -> actualContent
                else -> "[${type.displayName}]"
            }
        }

    val imagePath by lazy { getFieldByName<String?>(instance, "field_imgPath") }
    val stickerMd5 by lazy {
        imagePath?.takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlAttr(content, "md5").takeIf { it.isNotBlank() }
            ?: XmlUtils.extractXmlTag(content, "md5").takeIf { it.isNotBlank() }
    }
    /** 微信在没有该列 (或 ContentValues 中无 `lvbuffer` 键) 时会留 null, 因此必须可空 */
    val lvBuffer by lazy { getFieldByName<ByteArray?>(instance, "field_lvbuffer") }
    val talkerId by lazy { getFieldByName<Int>(instance, "field_talkerId") }
    val seq by lazy { getFieldByName<Long>(instance, "field_msgSeq") }

    val msgSource: String by lazy {
        val buffer = lvBuffer ?: return@lazy ""
        if (buffer.isEmpty()) return@lazy ""
        if (buffer[0] != '{'.code.toByte() || buffer.last() != '}'.code.toByte()) return@lazy ""

        val bb = ByteBuffer.wrap(buffer)
        bb.position(1) // skip '{'

        // skip string field (2-byte length prefix + data)
        if (bb.remaining() >= 2) {
            val n1 = bb.short.toInt()
            if (n1 > 3072) error("Buffer String Length Error")
            if (n1 != 0 && bb.remaining() >= n1) bb.position(bb.position() + n1)
        }

        // skip 4-byte int field
        if (bb.remaining() >= 4) bb.position(bb.position() + 4)

        if (bb.remaining() < 2) return@lazy ""

        val n2 = bb.short.toInt()
        if (n2 > 3072) error("Buffer String Length Error")
        if (n2 == 0 || bb.remaining() < n2) return@lazy ""

        val bytes = ByteArray(n2)
        bb.get(bytes)
        String(bytes, Charsets.UTF_8)
    }

    val mentionedUsers: List<String> by lazy {
        if (msgSource.isEmpty()) return@lazy emptyList()
        val xml = try {
            NativeXmlParser.toXmlObject(msgSource)
        } catch (_: Exception) {
            return@lazy emptyList()
        }
        val atUserListStr = xml.getByPath("msgsource.atuserlist")?.asString ?: return@lazy emptyList()
        atUserListStr.split(",").filter { it.isNotEmpty() }
    }

    val isAtMe get() = mentionedUsers.contains(WeApi.selfWxId)

    val isAnnounceAll get() = mentionedUsers.contains("announcement@all")

    val isNotifyAll: Boolean
        get() {
            if (mentionedUsers.contains("notify@all") || mentionedUsers.contains("announcement@all")) {
                val contentText = actualContent
                return contentText.contains("@所有人") || contentText.contains("@ all people")
            }
            return false
        }

    val isInGroupChat get() = talker.isGroupChatWxId
    val isOfficialAccount get() = talker.startsWith("gh_")
    val sender by lazy {
        @Suppress("DEPRECATION")
        if (typeCode == MessageType.SYSTEM.code) {
            return@lazy "system"
        }

        if (typeCode == MessageType.PAT.code) {
            val patMsg = PatMessage(content)
            return@lazy patMsg.fromUser
        }

        if (isSelfSender) {
            return@lazy WeApi.selfWxId
        }

        if (!isInGroupChat) {
            return@lazy talker
        }

        return@lazy content.split(':')[0]
    }

    val isSelfSender get() = isSend != 0

    inline fun toPatMessage(): PatMessage? {
        if (typeCode != MessageType.PAT.code)
            return null

        return PatMessage(content)
    }

    fun toQuoteMessage(): QuoteMessage? {
        if (typeCode != MessageType.QUOTE.code)
            return null

        return QuoteMessage(content)
    }

    fun toTransferMessage(): TransferMessage? {
        if (type != MessageType.TRANSFER)
            return null

        return TransferMessage(content)
    }

    fun toFileMessage(): FileMessage? {
        if (type != MessageType.FILE)
            return null

        return FileMessage(content)
    }

    fun toImageMessage(): ImageMessage? {
        if (type != MessageType.IMAGE)
            return null

        return ImageMessage(content)
    }

    class FileMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val size by lazy { xml.getByPath("msg.appmsg.appattach.totallen")!!.asLong }
        val ext by lazy { xml.getByPath("msg.appmsg.appattach.fileext")!!.asString }
        val md5 by lazy { xml.getByPath("msg.appmsg.md5")!!.asString }
        val url by lazy { xml.getByPath("msg.appmsg.appattach.cdnattachurl")!!.asString }
        val key by lazy { xml.getByPath("msg.appmsg.appattach.aeskey")!!.asString }

        /**
         * appmsg 内层 `<type>`:
         * - 6 / 130: 文件已就绪, 可以下载
         * - 74 / 131: 对方仍在上传中, 此时触发下载必然失败
         */
        val appMsgType by lazy { xml.getByPath("msg.appmsg.type")?.asInt }

        /** 对方是否仍在上传 (气泡显示"对方上传中")。 */
        val isSenderUploading get() = appMsgType == 74 || appMsgType == 131

        /** 文件是否已就绪、可下载。 */
        val isDownloadable get() = appMsgType == 6 || appMsgType == 130
    }

    class ImageMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val md5 by lazy { xml.getByPath("msg.img.md5")!!.asString }
        val bigImgUrl by lazy { xml.getByPath("msg.img.cdnbigimgurl")!!.asString }
        val midImgUrl by lazy { xml.getByPath("msg.img.cdnmidimgurl")!!.asString }
        val thumbUrl by lazy { xml.getByPath("msg.img.cdnthumburl")!!.asString }
        val aesKey by lazy { xml.getByPath("msg.img.aeskey")!!.asString }
    }

    class PatMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        /**
         * 拍一拍消息可以包含多条记录: 微信会把同一会话里接连的拍一拍合并进同一条消息,
         * 此时 `<record>` 是数组而不是单个节点。
         */
        val records: List<PatRecord> by lazy {
            when (val node = xml.getByPath("msg.appmsg.patMsg.records.record")) {
                is XmlArray -> node.mapNotNull { it as? XmlObject }.map(::PatRecord)
                is XmlObject -> listOf(PatRecord(node))
                else -> emptyList()
            }
        }

        private val latestRecord: PatRecord get() = records.last()

        val createTime by lazy { latestRecord.createTime }
        val fromUser by lazy { latestRecord.fromUser }
        val pattedUser by lazy { latestRecord.pattedUser }
        val readStatus by lazy { latestRecord.readStatus }
        val recordNum by lazy { xml.getByPath("msg.appmsg.patMsg.records.recordNum")?.asInt ?: records.size }
        val showModifyTip by lazy { latestRecord.showModifyTip }
        val svrId by lazy { latestRecord.svrId }
        val talker by lazy { xml.getByPath("msg.appmsg.patMsg.chatUser")!!.asString }
        val template by lazy { latestRecord.template }
        val recordObj by lazy { latestRecord.obj }

        class PatRecord(val obj: XmlObject) {
            val createTime by lazy { obj["createTime"]!!.asLong }
            val fromUser by lazy { obj["fromUser"]!!.asString }
            val pattedUser by lazy { obj["pattedUser"]!!.asString }
            val readStatus by lazy { obj["readStatus"]!!.asInt }
            val showModifyTip by lazy { obj["showModifyTip"]!!.asInt }
            val svrId by lazy { obj["svrId"]!!.asLong }
            val template by lazy { obj["template"]!!.asString }
        }
    }

    class QuoteMessage(xmlStr: String) {
        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val chatusr by lazy { xml.getByPath("msg.appmsg.refermsg.chatusr")!!.asString }
        val displayname by lazy { xml.getByPath("msg.appmsg.refermsg.displayname")!!.asString }
        val msgsource by lazy { xml.getByPath("msg.appmsg.refermsg.msgsource")!!.asString }
        val svrid by lazy { xml.getByPath("msg.appmsg.refermsg.svrid")!!.asString.toLong() }
        val fromusr by lazy { xml.getByPath("msg.appmsg.refermsg.fromusr")!!.asString }
        val type by lazy { xml.getByPath("msg.appmsg.refermsg.type")!!.asString.toInt() }
        val content by lazy { xml.getByPath("msg.appmsg.refermsg.content")!!.asString }
    }

    class TransferMessage(xmlStr: String) {

        private val xml = NativeXmlParser.toXmlObject(xmlStr.cleanupXml())

        val title by lazy { xml.getByPath("msg.appmsg.title")!!.asString }
        val des by lazy { xml.getByPath("msg.appmsg.des")!!.asString }

        // 'transcationid' is WeChat's typo
        val transactionId by lazy { xml.getByPath("msg.appmsg.wcpayinfo.transcationid")!!.asString }
        val transferId by lazy { xml.getByPath("msg.appmsg.wcpayinfo.transferid")!!.asString }
        val beginTransferTime by lazy { xml.getByPath("msg.appmsg.wcpayinfo.begintransfertime")!!.asString.toLong() }
        val payerUsername by lazy { xml.getByPath("msg.appmsg.wcpayinfo.payer_username")!!.asString }
        val receiverUsername by lazy { xml.getByPath("msg.appmsg.wcpayinfo.receiver_username")!!.asString }
        val invalidTime by lazy { xml.getByPath("msg.appmsg.wcpayinfo.invalidtime")!!.asString.toInt() }
        val feedesc by lazy { xml.getByPath("msg.appmsg.wcpayinfo.feedesc")!!.asString }
        val totalFee by lazy {
            xml.getByPath("msg.appmsg.wcpayinfo.total_fee")?.asString?.toLongOrNull() ?: 0L
        }
        val feeType by lazy {
            xml.getByPath("msg.appmsg.wcpayinfo.fee_type")?.asString.orEmpty()
        }
        val payMemo by lazy {
            xml.getByPath("msg.appmsg.wcpayinfo.pay_memo")?.asString.orEmpty()
        }
    }

    companion object {
        /**
         * Every bind creates a [MessageInfo] and several features read its fields while the message
         * list scrolls. `reflekt().getField(...)` rescans the class hierarchy on each call, so the
         * resolved [java.lang.reflect.Field] is cached per message class instead.
         */
        @Suppress("UNCHECKED_CAST")
        private inline fun <T> getFieldByName(instance: Any, name: String): T {
            return instance.cachedField(name).get(instance) as T
        }

        /**
         * Safely extracts tag content directly from raw XML strings.
         * Bypasses JSON type coercion overhead and prevents 32-digit string truncation.
         */
        private fun extractXmlTag(xml: String, tag: String): String? {
            val startTag = "<$tag>"
            val endTag = "</$tag>"
            if (!xml.contains(startTag) || !xml.contains(endTag)) return null

            val content = xml.substringAfter(startTag).substringBefore(endTag)
            return if (content.startsWith("<![CDATA[")) {
                content.substringAfter("<![CDATA[").substringBefore("]]>")
            } else {
                content
            }.trim()
        }

        private fun String.cleanupXml(): String {
            return "<msg>" + substringAfter("<msg>")
                .substringBeforeLast("</msg>")
                .replace("\r", "")
                .replace("\n", "")
                .replace("\t", "")
                .replace("<?xml version=\"1.0\"?>", "") + "</msg>"
        }

        fun fromContentValues(contentValues: ContentValues): MessageInfo {
            return MessageInfo(WeMessageApi.convertMsgInfoInstanceFromContentValues(contentValues))
        }
    }
}
