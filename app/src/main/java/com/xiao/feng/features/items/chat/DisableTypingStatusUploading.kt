package com.xiao.feng.features.items.chat

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object DisableTypingStatusUploading : SwitchFeature(), IResolveDex {

    override val technicalId = "禁止上传正在输入状态"
    override val nameRes = R.string.feature_disable_typing_status_uploading_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_disable_typing_status_uploading_description

    private val classMmTypingSendReq by dexClass()

    override fun onEnable() {
        if (classMmTypingSendReq.isPlaceholder) return

        classMmTypingSendReq.reflekt().firstMethod { name = "doScene" }
            .hookBefore {
                result = -1
            }
    }

}
