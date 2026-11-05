package com.xiao.feng.features.items.chat

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object DisablePat : SwitchFeature(), IResolveDex {

    override val technicalId = "禁用拍一拍"
    override val nameRes = R.string.feature_disable_pat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_pat_description

    private val methodAvatarDoubleClick by dexMethod()

    override fun onEnable() {
        methodAvatarDoubleClick.hookBefore {
            result = true
        }
    }
}
