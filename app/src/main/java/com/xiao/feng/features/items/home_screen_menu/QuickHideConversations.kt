package com.xiao.feng.features.items.home_screen_menu

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import com.xiao.feng.R
import com.xiao.feng.activity.TransparentActivity
import com.xiao.feng.features.api.core.WeConversationApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.features.api.ui.WeHomeScreenPopupMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.utils.VisibilityOffIcon
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast

/**
 * Adds a home-screen menu entry that lets the user multi-select conversations and hide them in
 * bulk. Hiding only removes the conversation row; history and the talker remain untouched, and a
 * new incoming message brings the conversation back (WeChat's own semantics).
 */
object QuickHideConversations : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "一键隐藏对话"
    override val nameRes = R.string.feature_quick_hide_conversations_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_quick_hide_conversations_description

    private const val TAG = "QuickHideConversations"
    private const val MENU_ITEM_ID = 777022

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> = listOf(
        WeHomeScreenPopupMenuApi.MenuItem(
            MENU_ITEM_ID,
            localizedHomeMenuString(R.string.home_menu_quick_hide_conversations),
            VisibilityOffIcon,
        ) {
            TransparentActivity.launch(HostInfo.application) {
                showContactsPicker()
            }
        }
    )

    private fun FragmentActivity.showContactsPicker() {
        val conversations: List<IWeContact> = runCatching {
            WeDatabaseApi.getFriends().map { it as IWeContact } +
                WeDatabaseApi.getGroups().map { it as IWeContact }
        }.getOrElse {
            WeLogger.e(TAG, "failed to load conversations", it)
            emptyList()
        }
        if (conversations.isEmpty()) {
            showToast(HostLocalizedStrings.get(R.string.quick_hide_no_conversations))
            finish()
            return
        }

        showComposeDialog(this) {
            ContactsSelector(
                title = HostLocalizedStrings.get(R.string.quick_hide_select_title),
                contacts = conversations,
                initialSelectedWxIds = emptySet(),
                onDismiss = {
                    onDismiss()
                    finish()
                },
                onConfirm = { selected ->
                    onDismiss()
                    if (selected.isEmpty()) {
                        showToast(HostLocalizedStrings.get(R.string.quick_hide_select_empty))
                        finish()
                    } else {
                        hideAll(selected)
                        finish()
                    }
                },
            )
        }
    }

    private fun hideAll(wxIds: Set<String>) {
        showToast(HostLocalizedStrings.get(R.string.quick_hide_hiding, wxIds.size))
        Thread {
            var hidden = 0
            wxIds.forEach { wxId ->
                runCatching { WeConversationApi.hideConversation(wxId) }
                    .onSuccess { if (it) hidden++ }
                    .onFailure { WeLogger.e(TAG, "failed to hide $wxId", it) }
            }
            Handler(Looper.getMainLooper()).post {
                showToast(HostLocalizedStrings.get(R.string.quick_hide_done, hidden, wxIds.size))
            }
        }.start()
    }
}
