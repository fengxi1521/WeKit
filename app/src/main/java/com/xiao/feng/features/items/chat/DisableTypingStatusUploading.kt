package com.xiao.feng.features.items.chat

import com.xiao.feng.R
import com.xiao.feng.dexkit.abc.IResolveDex
import com.xiao.feng.dexkit.dsl.dexClass
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge

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

    override fun resolveDex(dexKit: DexKitBridge) {
        classMmTypingSendReq.find(dexKit, allowFailure = true) {
            searchPackages("com.tencent.mm.modelsimple")
            matcher {
                usingEqStrings(
                    "null cannot be cast to non-null type com.tencent.mm.protocal.MMTypingSend.Req",
                    "autoAuth"
                )
            }
        }
    }
}
