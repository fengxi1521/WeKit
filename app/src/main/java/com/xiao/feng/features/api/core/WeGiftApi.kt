package com.xiao.feng.features.api.core

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexConstructor
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.WeLogger

/**
 * 微信礼物 (ECS gift) 的静默领取服务。
 *
 * 宿主在收到礼物 appmsg 后会构造一个 `Runnable` 任务，任务内部解析消息 XML 里的 orderId 并
 * 发送 `mmec_getpresentorder` CGI。这里直接复用它, 避免自己维护一套礼物协议。
 */
object WeGiftApi : ApiFeature(), IResolveDex {

    override val technicalId = "礼物领取服务"
    override val nameRes = R.string.feature_we_gift_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_gift_api_description

    private const val TAG = "WeGiftApi"

    private val classGiftService by dexClass()
    private val classGiftServiceImpl by dexClass()
    private val ctorGiftTask by dexConstructor()

    /**
     * 执行一次静默领取。调用方需要传入宿主自己的消息对象 (与 `MsgInfoStorage.insertMessage`
     * 的入参同一个实例)。
     */
    fun grab(msgInfo: Any): Boolean {
        return try {
            val service = resolveService()
            (ctorGiftTask.newInstance(service, msgInfo) as Runnable).run()
            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to run gift task", e)
            false
        }
    }

    // 服务容器按接口注册; 个别分支会按实现类注册, 两个都试一次。
    private fun resolveService(): Any =
        runCatching { WeServiceApi.getServiceByClass(classGiftService.clazz) }
            .getOrElse { WeServiceApi.getServiceByClass(classGiftServiceImpl.clazz) }
}
