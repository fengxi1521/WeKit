package com.xiao.feng.features.items.miniapps

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.enumValueOfClass
import java.lang.reflect.Modifier

object RemoveMenuLimits : SwitchFeature(), IResolveDex {

    override val technicalId = "去除菜单限制"
    override val nameRes = R.string.feature_remove_menu_limits_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_menu_limits_description

    private lateinit var showAndClickableEnumValue: Any

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        listOf(
            methodGetMenuItemVisibility1,
            methodGetMenuItemVisibility2
        ).forEach {
            it.hookBefore {
                if (!::showAndClickableEnumValue.isInitialized) {
                    val returnType = methodGetMenuItemVisibility1.method.returnType
                    showAndClickableEnumValue = enumValueOfClass(returnType, "SHOW_CLICKABLE")
                }
                result = showAndClickableEnumValue
            }
        }
    }

    private val methodGetMenuItemVisibility1 by dexMethod()

    private val methodGetMenuItemVisibility2 by dexMethod()
}
