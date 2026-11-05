package com.xiao.feng.features.items.voip

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import java.lang.reflect.Modifier

object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {

    override val technicalId = "移除通话时聊天限制"
    override val nameRes = R.string.feature_remove_limits_during_calls_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.VOIP)
    override val descriptionRes = R.string.feature_remove_limits_during_calls_description

    override fun onEnable() {
        listOf(
            methodIsDuringCall,
            methodIsMultiTalking,
            methodIsMultiTalking2,
            methodIsCameraUsing,
            methodIsCameraUsing2,
            methodIsVoiceUsing,
            methodIsVoiceUsing2,
            methodCheckAppBrandVoiceUsing,
            methodCheckAppBrandVoiceUsing2,
            methodMultiTalkCallBack,
            methodMultiTalkCallBackV2,
            methodVoipCallBack,
            methodIpCallCallBack,
            methodFlutterLinkVoipCallBack
        ).forEach {
            if (it.isPlaceholder) return@forEach
            it.hookBefore {
                result = false
            }
        }
    }

    private val methodIsDuringCall by dexMethod()
    private val methodIsMultiTalking by dexMethod()

    private val methodIsMultiTalking2 by dexMethod()
    private val methodIsCameraUsing by dexMethod()
    private val methodIsCameraUsing2 by dexMethod()
    private val methodIsVoiceUsing by dexMethod()
    private val methodIsVoiceUsing2 by dexMethod()
    private val methodCheckAppBrandVoiceUsing by dexMethod()
    private val methodCheckAppBrandVoiceUsing2 by dexMethod()

    // 8.0.77 moved the multitalk action listener out of
    // com.tencent.mm.plugin.multitalk.model (MultiTalkManager$18) into
    // com.tencent.mm.voipmp.v2 (VoIPMPCoreV2$multiTalkActionListener$1),
    // so this resolver has no result on 8.0.77 and the next one has no
    // result before it.
    private val methodMultiTalkCallBack by dexMethod()

    private val methodMultiTalkCallBackV2 by dexMethod()

    private val methodVoipCallBack by dexMethod()

    private val methodIpCallCallBack by dexMethod()

    private val methodFlutterLinkVoipCallBack by dexMethod()
}
