package com.xiao.feng.features.api.net

import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds

object WeNetSceneApi : ApiFeature(), IResolveDex {

    override val technicalId = "NetScene 服务"
    override val nameRes = R.string.feature_we_net_scene_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_net_scene_api_description

    fun sendNetScene(netScene: Any) {
        val queue = WeDatabaseApi.classMmKernel.clazz.reflekt()
            .firstMethod {
                returnType = methodAddNetSceneToQueue.method.declaringClass
            }.invokeStatic()!!
        methodAddNetSceneToQueue.method.invoke(queue, netScene, 0)
    }

    val methodAddNetSceneToQueue by dexMethod()
}
