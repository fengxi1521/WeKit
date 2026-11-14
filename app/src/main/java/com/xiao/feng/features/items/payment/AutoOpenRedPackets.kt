package com.xiao.feng.features.items.payment

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.utils.createInstance
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.WePaymentApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.net.WeNetSceneApi
import com.xiao.feng.features.items.payment.RedPacketSettings.ReceiveMode
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getTopMostActivity
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("DiscouragedApi")
object AutoOpenRedPackets : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    IResolveDex {

    override val technicalId = "自动抢红包"
    override val nameRes = R.string.feature_auto_open_red_packets_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_auto_open_red_packets_description

    private const val TAG = "AutoOpenRedPackets"

    /** 企业微信/OpenIM 群红包使用 union 领取链路, 对应源码中的 sceneid == 1005 */
    private const val UNION_SCENE_ID = 1005
    private const val EXTRA_CLICK_RECEIVE = "Nuke.AutoReceiveRedPacket.ClickReceive"
    private const val EXTRA_CLICK_RECEIVE_SCHEDULED = "Nuke.AutoReceiveRedPacket.ClickReceiveScheduled"

    /** 与宿主 LuckyMoneyNotHookReceiveUI 兼容的领取页启动参数 (来自 Nuke 1.0.2 hh.m)。 */
    private val REPORT_KEY_COMMON_REPORT_OBJ = byteArrayOf(
        10, 109, 10, 76, 97, 117, 110, 99, 104, 101, 114, 85, 73, 18, 36,
        48, 54, 99, 48, 55, 49, 101, 56, 45, 55, 52, 50, 98, 45, 52, 48, 97, 99, 45, 97, 100, 54, 48, 45, 57, 57, 101, 51, 102, 55, 100, 98, 99, 50, 55, 49,
        24, -57, -89, 50, 32, 0, 40, 1, 50, 46, 8, 9, 18, 42, 10, 20,
        49, 49, 53, 56, 51, 50, 56, 48, 50, 54, 57, 52, 51, 53, 57, 56, 49, 56, 52, 54,
        40, 0, 48, 0, 56, 0, 64, 0, 72, 0, 80, 0, 88, 0, 96, 0, 104, 0, 112, 0, 120, 0,
    )

    private val classReceiveLuckyMoneyUnion by dexClass()
    private val classOpenLuckyMoneyUnion by dexClass()
    private val classLuckyMoneyNotHookReceiveUI by dexClass()
    private val methodReceiveUnionOnGYNetEnd by dexMethod()
    private val methodOpenUnionOnGYNetEnd by dexMethod()

    private val currentRedPacketMap = ConcurrentHashMap<String, RedPacketInfo>()

    /** 已经发出领取请求的 sendId, 用于合并 insertMessage 与数据库两条检测路径, 避免重复发包。 */
    private val dispatchedSendIds = ConcurrentHashMap.newKeySet<String>()

    /** 命中暂停关键词后的恢复时间点 (毫秒时间戳), 0 表示未暂停。 */
    @Volatile
    private var pausedUntil = 0L

    private var scope = newScope()

    private data class RedPacketInfo(
        val sendId: String,
        val nativeUrl: String,
        val talker: String,
        val msgType: Int,
        val channelId: Int,
        val sceneId: Int = 0,
        val receiveMode: ReceiveMode = ReceiveMode.NETWORK,
        val headImg: String = "",
        val nickName: String = "",
        val notificationEnabled: Boolean = false,
        val autoReply: String = "",
        /** 解析红包时就算好的随机回复延时, 避免回复路径再碰设置。 */
        val autoReplyDelayMs: Long = 0L,
    )

    /** 8.0.74+ 的 union 打开请求多了 left_button_continue 参数 */
    private val unionOpenHasLeftButtonContinue: Boolean by lazy {
        classOpenLuckyMoneyUnion.clazz.declaredConstructors.any { it.parameterCount == 10 }
    }

    override fun onEnable() {
        RedPacketSettings.requireReady()
        RedPacketSettings.ensureGroupBaseline()
        if (scope.coroutineContext[Job]?.isActive != true) scope = newScope()
        // 重复开启时先摘掉旧监听, 避免同一条消息被分发多次。
        WeDatabaseListenerApi.removeListener(this)
        WeDatabaseListenerApi.addListener(this)

        // 最早的检测点: 宿主把消息写进数据库之前就入队领取请求, 既比 insert hook 更早,
        // 也不受其它 insert 监听器耗时的影响。数据库监听保留为兜底。
        WeMessageApi.methodMsgInfoStorageInsertMessage.hookBefore {
            val msgInfo = MessageInfo(args[0]!!)
            if (msgInfo.type?.isRedPacket == true) {
                handleRedPacket(msgInfo)
            }
        }

        WePaymentApi.methodReceiveLuckyMoneyOnGYNetEnd.hookAfter {
            handleReceiveResponse(args[2] as? JSONObject)
        }
        methodReceiveUnionOnGYNetEnd.hookAfter {
            handleReceiveResponse(args[2] as? JSONObject)
        }

        WePaymentApi.methodOpenLuckyMoneyOnGYNetEnd.hookAfter {
            handleOpenResponse(args[2] as? JSONObject)
        }
        methodOpenUnionOnGYNetEnd.hookAfter {
            handleOpenResponse(args[2] as? JSONObject)
        }

        classLuckyMoneyNotHookReceiveUI.clazz.declaredMethods
            .filter { it.name == "onSceneEnd" }
            .forEach { method ->
                method.hookAfter(50) {
                    val activity = thisObject as Activity
                    val intent = activity.intent
                    if (intent.getBooleanExtra(EXTRA_CLICK_RECEIVE, false) &&
                        !intent.getBooleanExtra(EXTRA_CLICK_RECEIVE_SCHEDULED, false)
                    ) {
                        intent.putExtra(EXTRA_CLICK_RECEIVE_SCHEDULED, true)
                        val button = findFirstButton(activity.window.decorView)
                        if (button != null) {
                            WeLogger.i(TAG, "auto-clicking receive button in host UI")
                            button.performClick()
                        }
                    }
                }
            }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "rcontact") {
            handleNewContact(values)
            return
        }
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        val messageType = MessageType.fromCode(type)
        if (messageType?.isRedPacket == true) {
            WeLogger.i(TAG, "detected red packet message; type=$type")
            handleRedPacket(MessageInfo.fromContentValues(values))
            return
        }
        if (messageType?.isText == true) {
            handlePauseKeywordMessage(values)
        }
    }

    /** 新进群保护: 第一次见到的群写入“不抢红包”的会话规则, 之后可在群设置里手动打开。 */
    private fun handleNewContact(values: ContentValues) {
        if (!RedPacketSettings.blockNewGroups) return
        val username = values.getAsString("username") ?: return
        if (!username.isGroupChatWxId) return
        if (!RedPacketSettings.registerGroup(username)) return
        scope.launch {
            try {
                RedPacketSettings.disableGrabForContact(username)
                WeLogger.i(TAG, "new group $username: red packet grabbing disabled")
            } catch (e: Exception) {
                WeLogger.w(TAG, "failed to disable red packet grabbing for new group $username", e)
            }
        }
    }

    /** 有人发出指定文字时暂停抢红包 [RedPacketSettings.PauseRule] 设定的秒数, 到点后自动恢复。 */
    private fun handlePauseKeywordMessage(values: ContentValues) {
        try {
            val msgInfo = MessageInfo.fromContentValues(values)
            if (msgInfo.isSelfSender) return

            val settings = RedPacketSettings.resolve(
                msgInfo.talker,
                msgInfo.sender.takeIf { msgInfo.isInGroupChat },
            )
            if (!settings.pause.enabled) return

            val duration = settings.pause.durationMillis()
            if (duration <= 0L) return
            if (!settings.pause.activeKeywords.matches(msgInfo.content)) return

            pausedUntil = System.currentTimeMillis() + duration
            WeLogger.i(
                TAG,
                "paused grabbing for ${duration}ms after keyword message in ${msgInfo.talker}",
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to check pause keyword message", e)
        }
    }

    private fun handleRedPacket(msgInfo: MessageInfo) {
        if (System.currentTimeMillis() < pausedUntil) {
            WeLogger.i(TAG, "skipping red packet: grabbing paused by keyword")
            return
        }
        try {
            val talker = msgInfo.talker
            val content = msgInfo.content
            val isGroupChat = msgInfo.isInGroupChat
            val sender = msgInfo.sender
            val settings = RedPacketSettings.resolve(talker, sender.takeIf { isGroupChat })

            if (!settings.grab.enabled) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: grabbing disabled")
                return
            }
            if (msgInfo.isSelfSender && !settings.grabSelf.enabled) {
                WeLogger.i(TAG, "skipping self-sent packet in $talker")
                return
            }
            if (!settings.isInActiveTime()) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: outside active time range")
                return
            }

            var xmlContent = content
            if (!content.startsWith("<") && content.contains(":")) {
                xmlContent = content.substring(content.indexOf(":") + 1).trim()
            }

            val nativeUrl = extractXmlParam(xmlContent, "nativeurl")
            if (nativeUrl.isEmpty()) return

            val uri = nativeUrl.toUri()
            val msgType = uri.getQueryParameter("msgtype")?.toIntOrNull() ?: 1
            val channelId = uri.getQueryParameter("channelid")?.toIntOrNull() ?: 1
            val sceneId = extractXmlParam(xmlContent, "sceneid").toIntOrNull() ?: 0
            val sendId = uri.getQueryParameter("sendid") ?: ""
            val headImg = extractXmlParam(xmlContent, "headimgurl")
            val nickName = extractXmlParam(xmlContent, "sendertitle")

            if (sendId.isEmpty()) return
            if (settings.skipKeyword.enabled && settings.skipKeyword.matches(nickName)) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: skip keyword matched")
                return
            }
            if (!settings.matchesKeyword(nickName)) {
                WeLogger.i(TAG, "skipping packet from $sender in $talker: keyword did not match")
                return
            }

            WeLogger.i(TAG, "detected red packet (sendId=$sendId)")

            // 两条检测路径可能命中同一条消息, 只让第一个真正发包。
            if (!dispatchedSendIds.add(sendId)) {
                WeLogger.d(TAG, "skipping already dispatched packet (sendId=$sendId)")
                return
            }

            val info = RedPacketInfo(
                sendId = sendId,
                nativeUrl = nativeUrl,
                talker = talker,
                msgType = msgType,
                channelId = channelId,
                sceneId = sceneId,
                receiveMode = settings.receiveMode,
                headImg = headImg,
                nickName = nickName,
                notificationEnabled = settings.notification.enabled,
                autoReply = settings.autoReply.pickText().orEmpty(),
                autoReplyDelayMs = settings.autoReply.delayMillis(),
            )
            currentRedPacketMap[sendId] = info

            val delayTime = settings.delayMillis()
            WeLogger.i(TAG, "resolved delay: ${delayTime}ms (sendId=$sendId)")

            if (delayTime <= 0L) {
                // 竞速场景: 没有配置延时就地在当前线程入队, 省掉一次协程调度。
                sendReceiveRequest(info)
            } else {
                WeLogger.i(TAG, "started delaying for ${delayTime}ms (sendId=$sendId)")
                scope.launch {
                    delay(delayTime)
                    sendReceiveRequest(info)
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to parse red packet data", e)
        }
    }

    private fun sendReceiveRequest(info: RedPacketInfo) {
        try {
            if (info.receiveMode == ReceiveMode.CLICK) {
                WeLogger.i(TAG, "click receive mode: launching host receive UI (sendId=${info.sendId})")
                launchClickReceiveUi(info)
                return
            }

            val req = if (info.sceneId == UNION_SCENE_ID) {
                WeLogger.i(TAG, "using union receive request (sendId=${info.sendId})")
                // 与宿主 LuckyMoneyNewReceiveUI 一致: union 领取请求固定 msgType=1 且不携带群名
                classReceiveLuckyMoneyUnion.clazz.createInstance(
                    1, info.channelId, info.sendId, info.nativeUrl, 1 /* inWay */, "v1.0" /* ver */
                )
            } else {
                WePaymentApi.classReceiveLuckyMoney.clazz.createInstance(
                    info.msgType, info.channelId, info.sendId, info.nativeUrl,
                    1 /* inWay */, "v1.0" /* ver */, info.talker
                )
            }

            WeNetSceneApi.sendNetScene(req)
            WeLogger.i(TAG, "sent receive request (sendId=${info.sendId})")
        } catch (e: Throwable) {
            // 发包失败时撤销去重标记, 让另一条检测路径还有机会重试。
            dispatchedSendIds.remove(info.sendId)
            WeLogger.e(TAG, "failed to send receive request (sendId=${info.sendId})", e)
        }
    }

    private fun launchClickReceiveUi(info: RedPacketInfo) {
        val activity = getTopMostActivity()
        if (activity == null) {
            WeLogger.w(TAG, "no foreground activity for click receive (sendId=${info.sendId})")
            currentRedPacketMap.remove(info.sendId)
            dispatchedSendIds.remove(info.sendId)
            return
        }
        val intent = Intent(activity, classLuckyMoneyNotHookReceiveUI.clazz).apply {
            putExtra(EXTRA_CLICK_RECEIVE, true)
            putExtra("KEY_HOME_PAGE_CLS", "com.tencent.mm.ui.LauncherUI")
            putExtra("key_username", info.talker)
            putExtra("key_way", 1)
            putExtra("key_native_url", info.nativeUrl)
            putExtra("ReportKey.CommonReportObjKey", REPORT_KEY_COMMON_REPORT_OBJ)
            putExtra("key_cropname", "")
        }
        activity.runOnUiThread {
            try {
                activity.startActivity(intent)
                WeLogger.i(TAG, "opened red packet receive UI (sendId=${info.sendId})")
            } catch (e: Throwable) {
                WeLogger.e(TAG, "open red packet receive UI failed (sendId=${info.sendId})", e)
                currentRedPacketMap.remove(info.sendId)
            }
        }
    }

    private fun findFirstButton(container: View): android.widget.Button? {
        if (container is android.widget.Button) return container
        if (container !is ViewGroup) return null
        for (i in 0 until container.childCount) {
            findFirstButton(container.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun handleReceiveResponse(json: JSONObject?) {
        json ?: return

        val sendId = json.optString("sendId")
        val timingIdentifier = json.optString("timingIdentifier")

        if (timingIdentifier.isNullOrEmpty() || sendId.isNullOrEmpty()) return

        val info = currentRedPacketMap[sendId] ?: run {
            WeLogger.e(TAG, "failed to find red packet in map (sendId=$sendId)")
            return
        }
        WeLogger.i(
            TAG,
            "unpack request finished, sending open request (sendId=$sendId, sceneId=${info.sceneId})"
        )

        if (info.receiveMode == ReceiveMode.CLICK) {
            WeLogger.i(TAG, "click receive mode: host UI handles the open request (sendId=$sendId)")
            return
        }

        // 开包是决定能不能抢到的最后一步, 直接在回调线程入队, 不再绕一次协程调度。
        sendOpenRequest(info, timingIdentifier)
    }

    private fun sendOpenRequest(info: RedPacketInfo, timingIdentifier: String) {
        try {
            val openReq = if (info.sceneId == UNION_SCENE_ID) {
                WeLogger.i(TAG, "using union open request (sendId=${info.sendId})")
                createUnionOpenRequest(info, timingIdentifier)
            } else {
                WePaymentApi.classOpenLuckyMoney.clazz.createInstance(
                    info.msgType, info.channelId, info.sendId, info.nativeUrl,
                    info.headImg, info.nickName, info.talker,
                    "v1.0", timingIdentifier, ""
                )
            }
            WeNetSceneApi.sendNetScene(openReq)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to send open request (sendId=${info.sendId})", e)
            currentRedPacketMap.remove(info.sendId)
        }
    }

    private fun handleOpenResponse(json: JSONObject?) {
        json ?: return

        val sendId = json.optString("sendId")
        if (sendId.isNullOrEmpty()) return

        val info = currentRedPacketMap.remove(sendId) ?: return
        dispatchedSendIds.remove(sendId)

        val retCode = json.optInt("retcode", -1)
        if (retCode != 0) {
            WeLogger.w(TAG, "failed to grab packet (retcode=$retCode, sendId=$sendId)")
            return
        }

        val receiveStatus = json.optInt("receiveStatus", -1)
        if (receiveStatus != 2) {
            WeLogger.w(TAG, "missed the packet (recvStatus=$receiveStatus, sendId=$sendId)")
            return
        }

        val amount = json.optInt("amount", 0)
        if (amount <= 0) return

        RedPacketSettings.recordGrab(amount.toLong())
        val displayAmount = amount / 100.0

        val reply = info.autoReply
        if (reply.isNotBlank()) {
            val replyText = reply.replace($$"$amount", "¥$displayAmount")
            val replyDelay = info.autoReplyDelayMs
            if (replyDelay > 0L) {
                // 随机延时回复, 看起来不像秒回的机器人。
                scope.launch {
                    delay(replyDelay)
                    WeMessageApi.sendText(info.talker, replyText)
                }
            } else {
                WeMessageApi.sendText(info.talker, replyText)
            }
        }

        if (!info.notificationEnabled) return

        val displayName = WeDatabaseApi.getDisplayName(info.talker)
        val isGroup = info.talker.isGroupChatWxId
        val sourceLabel = localizedPaymentString(
            if (isGroup) R.string.payment_source_group else R.string.payment_source_private_chat
        )
        showToast(
            localizedPaymentString(
                R.string.payment_red_packet_received,
                sourceLabel,
                displayName,
                displayAmount,
            )
        )
    }

    private fun createUnionOpenRequest(info: RedPacketInfo, timingIdentifier: String): Any {
        val args = mutableListOf<Any?>(
            1, info.channelId, info.sendId, info.nativeUrl,
            info.headImg, info.nickName, info.talker,
            "v1.0", timingIdentifier
        )
        // 8.0.74+ 的 NetSceneOpenLuckyMoneyUnion 构造函数带 left_button_continue
        if (unionOpenHasLeftButtonContinue) args += ""
        return classOpenLuckyMoneyUnion.clazz.createInstance(*args.toTypedArray())
    }

    private fun extractXmlParam(xml: String, tag: String): String {
        // 热路径: 不用正则, 直接按标签边界截取, 省掉每次编译 Pattern 的开销。
        val cdataStart = "<$tag><![CDATA["
        if (xml.contains(cdataStart)) {
            return xml.substringAfter(cdataStart).substringBefore("]]></$tag>")
        }
        val start = "<$tag>"
        val end = "</$tag>"
        if (!xml.contains(start) || !xml.contains(end)) return ""
        return xml.substringAfter(start).substringBefore(end)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        currentRedPacketMap.clear()
        dispatchedSendIds.clear()
        pausedUntil = 0L
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onClick(context: ComponentActivity) {
        RedPacketSettings.showMainDialog(context)
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = stringResource(R.string.warning)) },
                    text = { Text(text = stringResource(R.string.payment_risk_warning)) },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) { Text(stringResource(R.string.dialog_confirm)) }
                    },
                    dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
                )
            }
            return false
        }

        return true
    }
}
