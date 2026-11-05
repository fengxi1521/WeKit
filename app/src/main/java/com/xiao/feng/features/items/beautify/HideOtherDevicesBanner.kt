package com.xiao.feng.features.items.beautify

import android.view.View
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object HideOtherDevicesBanner : SwitchFeature(), IResolveDex {

    override val technicalId = "隐藏其他设备横幅"
    override val nameRes = R.string.feature_hide_other_devices_banner_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_hide_other_devices_banner_description

    private val methodSetOtherOnlineBannerVisibility by dexMethod()

    override fun onEnable() {
        methodSetOtherOnlineBannerVisibility.hookBefore {
            if (args.isNotEmpty() && args[0] is Int) {
                args[0] = View.GONE
            }
        }
    }
}

