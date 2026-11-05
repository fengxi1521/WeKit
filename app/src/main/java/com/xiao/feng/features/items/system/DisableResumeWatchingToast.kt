package com.xiao.feng.features.items.system

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object DisableResumeWatchingToast : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用「刚刚在看」提醒"
    override val nameRes = R.string.feature_disable_resume_watching_toast_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_disable_resume_watching_toast_description

    private val methodShowRecoveryToast by dexMethod()

    override fun onEnable() {
        methodShowRecoveryToast.hookBefore {
            result = null
        }
    }
}
