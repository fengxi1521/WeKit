package com.xiao.feng.features.items.home_screen_menu

import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeConversationApi
import com.xiao.feng.features.api.ui.WeHomeScreenPopupMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.ui.utils.MarkChatReadIcon
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.android.showToast

object MarkAllAsRead : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "清空未读"
    override val nameRes = R.string.feature_mark_all_as_read_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_mark_all_as_read_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777012, localizedHomeMenuString(R.string.home_menu_mark_all_read), MarkChatReadIcon
            ) {
                WeConversationApi.markAllAsRead()
                showToast(localizedHomeMenuString(R.string.home_menu_all_marked_read))
            }
        )
    }
}
