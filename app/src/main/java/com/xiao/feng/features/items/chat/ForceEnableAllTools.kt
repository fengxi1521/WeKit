package com.xiao.feng.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexField
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object ForceEnableAllTools : SwitchFeature(), IResolveDex {

    override val technicalId = "强制启用所有功能"
    override val nameRes = R.string.feature_force_enable_all_tools_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_force_enable_all_tools_description

    private val classAppPanelConfig by dexClass()
    private val fieldAppPanelConfig by dexField()
    private val fieldFirstFlag by dexField()
    private val fieldFlagEnabled by dexField()
    private val methodRefreshAppPanel by dexMethod()

    override fun onEnable() {
        methodRefreshAppPanel.hookBefore {
            val appPanel = thisObject ?: return@hookBefore
            val config = fieldAppPanelConfig.field.get(appPanel) ?: return@hookBefore
            config.reflekt().fields {
                type = fieldFirstFlag.field.type
            }.forEach {
                fieldFlagEnabled.field.setBoolean(it.get()!!, true)
            }
        }
    }


    private const val APP_PANEL_PACKAGE = "com.tencent.mm.pluginsdk.ui.chat"
    private const val APP_PANEL_CLASS = "$APP_PANEL_PACKAGE.AppPanel"
}
