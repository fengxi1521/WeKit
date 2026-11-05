package com.xiao.feng.features.items.system

import android.app.Activity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object DisableLowAvailableStorageDetection : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用存储空间不足检测"
    override val nameRes = R.string.feature_disable_low_available_storage_detection_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_low_available_storage_detection_description

    private val methodSplashActivitySplashFinished by dexMethod()
    private val classStaticValuesHolder by dexClass()

    override fun onEnable() {
        methodSplashActivitySplashFinished.hookBefore {
            classStaticValuesHolder.clazz.reflekt()
                .firstField { type = Boolean::class }
                .setStatic(false)
        }

        "com.tencent.mm.plugin.clean.ui.fileindexui.StorageDisableAlertUI"
            .toClass().hookAfterOnCreate {
                val activity = thisObject as Activity
                activity.finish()
            }
    }
}
