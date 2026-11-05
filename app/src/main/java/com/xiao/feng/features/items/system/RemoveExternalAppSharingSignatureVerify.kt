package com.xiao.feng.features.items.system

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object RemoveExternalAppSharingSignatureVerify : SwitchFeature(), IResolveDex {

    override val technicalId = "移除分享签名校验"
    override val nameRes = R.string.feature_remove_external_app_sharing_signature_verify_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_remove_external_app_sharing_signature_verify_description

    private val methodSignCheck by dexMethod()

    override fun onEnable() {
        methodSignCheck.hookBefore {
            result = true
        }
    }
}
