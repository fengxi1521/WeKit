package com.xiao.feng.features.items.profile

import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexField
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object RemoveTextStatusLengthLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除状态词长度限制"
    override val nameRes = R.string.feature_remove_text_status_length_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_remove_text_status_length_limit_description

    private val methodStatusTextChanged by dexMethod()
    private val fieldStatusTextLengthLimit by dexField()


    override fun onEnable() {
        val limitField = fieldStatusTextLengthLimit.field
        limitField.declaringClass.reflekt().constructors().forEach { constructor ->
            constructor.hookAfter {
                limitField.setInt(thisObject!!, MAX_STATUS_TEXT_LENGTH)
            }
        }
    }

    private const val STATUS_EDITOR_CLASS =
        "com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2"
    private const val MAX_STATUS_TEXT_LENGTH = 2000
}
