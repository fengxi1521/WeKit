package com.xiao.feng.features.items.home_screen_menu

import android.content.Intent
import com.xiao.feng.R
import com.xiao.feng.features.api.ui.WeHomeScreenPopupMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.ui.utils.ChatInfoIcon
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast

/**
 * Adds a home-screen overflow menu item that jumps straight to the Moments message list.
 * WeChat treats the list's `onCreate` as "read", so the red dot clears as a side effect.
 */
object OpenMomentsMessages : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "朋友圈消息入口"
    override val nameRes = R.string.feature_open_moments_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_open_moments_messages_description

    private const val TAG = "OpenMomentsMessages"
    private const val MENU_ITEM_ID = 777021

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> = listOf(
        WeHomeScreenPopupMenuApi.MenuItem(
            MENU_ITEM_ID,
            localizedHomeMenuString(R.string.home_menu_moments_messages),
            ChatInfoIcon,
        ) {
            runCatching {
                HostInfo.application.startActivity(
                    Intent().apply {
                        setClassName(
                            HostInfo.packageName,
                            "com.tencent.mm.plugin.sns.ui.SnsMsgUI",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                WeLogger.e(TAG, "failed to launch Moments messages", it)
                showToast(
                    HostLocalizedStrings.get(R.string.open_moments_messages_failed) +
                        " (${it.javaClass.simpleName})"
                )
            }
        }
    )
}
