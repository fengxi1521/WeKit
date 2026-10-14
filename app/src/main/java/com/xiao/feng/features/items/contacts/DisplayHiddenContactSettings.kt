package com.xiao.feng.features.items.contacts

import android.widget.BaseAdapter
import com.tencent.mm.plugin.profile.ui.ProfileSettingUI
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object DisplayHiddenContactSettings : SwitchFeature() {

    override val technicalId = "显示隐藏朋友设置项"
    override val nameRes = R.string.feature_display_hidden_contact_settings_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_display_hidden_contact_settings_description

    override fun onEnable() {
        ProfileSettingUI::class.reflekt()
            .firstMethod {
                name = "initView"
            }.hookAfter {
                val prefScreen = thisObject!!.reflekt()
                    .firstMethod {
                        name = "getPreferenceScreen"
                        superclass()
                    }.invoke()!!
                val hiddenSet = prefScreen.reflekt()
                    .firstField {
                        type = HashSet::class
                    }.get()!! as HashSet<*>
                hiddenSet.clear()
                (prefScreen as BaseAdapter).notifyDataSetChanged()
            }
    }
}
