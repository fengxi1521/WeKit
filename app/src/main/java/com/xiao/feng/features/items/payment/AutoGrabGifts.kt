package com.xiao.feng.features.items.payment

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeGiftApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.serialization.XmlUtils
import java.util.Collections
import java.util.LinkedHashSet

/**
 * 收到礼物消息后在后台静默领取。
 *
 * 监听点是宿主把消息写入数据库之前的 `MsgInfoStorage.insertMessage`, 与自动打开红包使用同一个
 * 切入点; 此时可以直接拿到宿主自己的消息对象, 交给礼物任务执行领取。
 */
object AutoGrabGifts : ClickableFeature() {

    override val technicalId = "自动抢礼物"
    override val nameRes = R.string.feature_auto_grab_gifts_name
    override val categoryIds = listOf(FeatureCategoryIds.PAYMENT)
    override val descriptionRes = R.string.feature_auto_grab_gifts_description

    private const val TAG = "AutoGrabGifts"
    private const val KEY_DELAY_SECONDS = "auto_grab_gifts_delay_seconds"
    private const val MAX_DEDUP_ORDERS = 300

    var delaySeconds: Int by prefOption(KEY_DELAY_SECONDS, 0)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val handledOrders = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var hookInstalled = false

    override fun onEnable() {
        // Hook 无法摘除, 只需安装一次; 关闭后由 isEnabled 让回调直接返回。
        if (hookInstalled) return
        hookInstalled = true

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookBefore {
            if (!isEnabled) return@hookBefore

            val msgInfo = MessageInfo(args[0]!!)
            if (msgInfo.isSelfSender) return@hookBefore

            val content = msgInfo.content
            if (!content.contains("<orderid>")) return@hookBefore

            val split = content.indexOf(":\n")
            val xml = if (split > 0 && content.indexOf('<') > split) {
                content.substring(split + 2)
            } else {
                content
            }
            val orderId = XmlUtils.extractXmlTag(xml, "orderid").trim()
            if (orderId.isEmpty()) return@hookBefore
            if (!markHandled(orderId)) return@hookBefore

            val delayMillis = delaySeconds.coerceAtLeast(0) * 1000L
            WeLogger.i(TAG, "gift message detected, orderId=$orderId, delay=$delayMillis")
            mainHandler.postDelayed({ if (isEnabled) WeGiftApi.grab(msgInfo.instance) }, delayMillis)
        }
    }

    private fun markHandled(orderId: String): Boolean {
        synchronized(handledOrders) {
            if (!handledOrders.add(orderId)) return false
            if (handledOrders.size > MAX_DEDUP_ORDERS) {
                handledOrders.remove(handledOrders.first())
            }
            return true
        }
    }

    override fun onClick(context: ComponentActivity) = showAutoGrabGiftsDialog(context)
}
