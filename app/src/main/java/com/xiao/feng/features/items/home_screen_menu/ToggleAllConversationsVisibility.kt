package com.xiao.feng.features.items.home_screen_menu

import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeConversationApi
import com.xiao.feng.features.api.ui.WeHomeScreenPopupMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.ui.utils.VisibilityIcon
import com.xiao.feng.ui.utils.VisibilityOffIcon
import com.xiao.feng.utils.HookParam

object ToggleAllConversationsVisibility : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "显隐全部对话"
    override val nameRes = R.string.feature_toggle_all_conversations_visibility_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_toggle_all_conversations_visibility_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777010, localizedHomeMenuString(R.string.home_menu_show_conversations), VisibilityIcon
            ) {
                WeConversationApi.setAllConversationVisibility(true)
            },
            WeHomeScreenPopupMenuApi.MenuItem(
                777011, localizedHomeMenuString(R.string.home_menu_hide_conversations), VisibilityOffIcon
            ) {
                WeConversationApi.setAllConversationVisibility(false)
            },
        )
    }
}
