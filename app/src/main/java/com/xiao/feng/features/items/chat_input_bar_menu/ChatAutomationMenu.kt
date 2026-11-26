package com.xiao.feng.features.items.chat_input_bar_menu

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Format_quote
import com.composables.icons.materialsymbols.outlined.Redeem
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.xiao.feng.R
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.api.ui.WeSettingsInjector
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.chat.AutoRepeat
import com.xiao.feng.features.items.chat.showAutoRepeatDialog
import com.xiao.feng.features.items.chat.showFollowRepeatDialog
import com.xiao.feng.features.items.chat.ai.showAtReplyDialog
import com.xiao.feng.features.items.chat.ai.showAtReplyGroupDialog
import com.xiao.feng.features.items.payment.RedPacketSettings
import com.xiao.feng.utils.android.showToast

/**
 * 长按聊天输入栏 "+" 弹出的菜单里的设置入口：抢红包配置（全局 / 单独会话）和艾特回复。
 *
 * 抢红包配置不依赖当前聊天类型, 所以这里用常驻的 [ApiFeature], 任何会话都能打开。
 */
object ChatAutomationMenu : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    override val technicalId = "聊天自动化设置菜单"
    override val nameRes = R.string.feature_chat_automation_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_automation_menu_description

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        // 模块入口常驻最顶部, 点击直接进入小枫设置主页。
        WeChatInputBarMenuApi.ActionItem(
            id = "xfeng_module_settings",
            icon = MaterialSymbols.Outlined.Settings,
            label = localizedChatInputString(R.string.xfeng_module_menu_entry),
            onClick = { context, _ -> WeSettingsInjector.openSettingsDialog(context) },
            order = 0,
        ),
        WeChatInputBarMenuApi.ActionItem(
            id = "follow_repeat",
            icon = MaterialSymbols.Outlined.Content_copy,
            label = localizedChatInputString(R.string.feature_follow_repeat_name),
            onClick = { context, _ -> showFollowRepeatDialog(context) },
            order = 1,
        ),
        WeChatInputBarMenuApi.ActionItem(
            id = "auto_repeat_toggle",
            icon = MaterialSymbols.Outlined.Format_quote,
            label = localizedChatInputString(
                if (AutoRepeat.isEnabled) {
                    R.string.auto_repeat_menu_disable
                } else {
                    R.string.auto_repeat_menu_enable
                }
            ),
            // 单击直接开关, 长按进入完整的会话范围 / 阈值设置。
            onClick = { context, _ ->
                val next = !AutoRepeat.isEnabled
                AutoRepeat.applyToggle(next)
                showToast(
                    context,
                    localizedChatInputString(
                        if (next) R.string.auto_repeat_toast_enabled else R.string.auto_repeat_toast_disabled
                    )
                )
            },
            onLongClick = { context, _ -> showAutoRepeatDialog(context) },
            order = 2,
        ),
        WeChatInputBarMenuApi.ActionItem(
            id = "red_packet_settings",
            icon = MaterialSymbols.Outlined.Redeem,
            label = localizedChatInputString(R.string.red_packet_menu_entry),
            onClick = { context, _ -> RedPacketSettings.showMainDialog(context) },
            // 长按直接打开当前会话的规则, 不再走「单独会话设置 → 选择会话」两步。
            onLongClick = { context, _ ->
                val talker = WeCurrentConversationApi.value
                if (talker.isNotBlank()) RedPacketSettings.showConversationDialog(context, talker)
            },
        ),
        WeChatInputBarMenuApi.ActionItem(
            id = "at_reply_settings",
            icon = MaterialSymbols.Outlined.Smart_toy,
            label = localizedChatInputString(R.string.feature_at_reply_name),
            onClick = { context, _ -> showAtReplyDialog(context) },
            // 长按只调当前群的开关, 不影响其他群。
            onLongClick = { context, _ ->
                val talker = WeCurrentConversationApi.value
                if (talker.isNotBlank()) showAtReplyGroupDialog(context, talker)
            },
        ),
    )

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(this)
    }
}
