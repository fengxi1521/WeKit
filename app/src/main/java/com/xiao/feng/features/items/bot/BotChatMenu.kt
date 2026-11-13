package com.xiao.feng.features.items.bot

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Extension
import com.xiao.feng.R
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.chat.localizedChatString
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId

/**
 * 把内置机器人的开关放进长按聊天输入栏 "+" 弹出的菜单里，直接对当前群开/关。
 *
 * 白名单为空时脚本默认在所有群生效，所以关闭某个群写在 `disabled_group_list` 里；
 * 重新打开时移出关闭名单，必要时补进白名单。
 */
object BotChatMenu : ApiFeature() {

    override val technicalId = "机器人快捷开关菜单"
    override val nameRes = R.string.feature_bot_chat_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_chat_menu_description

    private class Entry(
        val scriptId: String,
        val nameRes: Int,
        val feature: SwitchFeature,
        val isEnabled: (String) -> Boolean,
        val setEnabled: (String, Boolean) -> Unit,
    )

    private val entries = listOf(
        Entry(
            scriptId = BotGroupRobot.SCRIPT_ID,
            nameRes = R.string.feature_bot_group_robot_name,
            feature = BotGroupRobot,
            isEnabled = { BotScriptSettings.isGroupEnabled(BotGroupRobot.SCRIPT_ID, it) },
            setEnabled = { group, enabled ->
                BotScriptSettings.setGroupEnabled(BotGroupRobot.SCRIPT_ID, group, enabled)
            },
        ),
        Entry(
            scriptId = BotEntertainmentGames.SCRIPT_ID,
            nameRes = R.string.feature_bot_games_name,
            feature = BotEntertainmentGames,
            isEnabled = { BotScriptSettings.isGroupEnabled(BotEntertainmentGames.SCRIPT_ID, it) },
            setEnabled = { group, enabled ->
                BotScriptSettings.setGroupEnabled(BotEntertainmentGames.SCRIPT_ID, group, enabled)
            },
        ),
        Entry(
            scriptId = BotNumberGuess.SCRIPT_ID,
            nameRes = R.string.feature_bot_number_guess_name,
            feature = BotNumberGuess,
            isEnabled = { BotScriptSettings.isGroupEnabled(BotNumberGuess.SCRIPT_ID, it) },
            setEnabled = { group, enabled ->
                BotScriptSettings.setGroupEnabled(BotNumberGuess.SCRIPT_ID, group, enabled)
            },
        ),
        Entry(
            scriptId = BotLifeRestart.SCRIPT_ID,
            nameRes = R.string.feature_bot_life_restart_name,
            feature = BotLifeRestart,
            isEnabled = { BotScriptSettings.isGroupEnabled(BotLifeRestart.SCRIPT_ID, it) },
            setEnabled = { group, enabled ->
                BotScriptSettings.setGroupEnabled(BotLifeRestart.SCRIPT_ID, group, enabled)
            },
        ),
        Entry(
            scriptId = BotMagicEmoji.SCRIPT_ID,
            nameRes = R.string.feature_bot_magic_emoji_name,
            feature = BotMagicEmoji,
            isEnabled = { BotScriptSettings.isGroupEnabled(BotMagicEmoji.SCRIPT_ID, it) },
            setEnabled = { group, enabled ->
                BotScriptSettings.setGroupEnabled(BotMagicEmoji.SCRIPT_ID, group, enabled)
            },
        ),
        Entry(
            scriptId = BotMoinMenu.SCRIPT_ID,
            nameRes = R.string.feature_bot_moin_menu_name,
            feature = BotMoinMenu,
            isEnabled = { BotMoinMenu.isGroupEnabled(it) },
            setEnabled = { group, enabled -> BotMoinMenu.setGroupEnabled(group, enabled) },
        ),
    )

    private val menuProvider = WeChatInputBarMenuApi.IActionItemsProvider {
        val talker = WeCurrentConversationApi.value
        if (talker.isBlank() || !talker.isGroupChatWxId) return@IActionItemsProvider emptyList()
        entries.map { entry ->
            val enabled = entry.feature.isEnabled && entry.isEnabled(talker)
            val name = localizedChatString(entry.nameRes)
            WeChatInputBarMenuApi.ActionItem(
                id = "bot_toggle_${entry.scriptId}",
                icon = MaterialSymbols.Outlined.Extension,
                label = localizedChatString(
                    if (enabled) R.string.bot_chat_menu_group_on else R.string.bot_chat_menu_group_off,
                    name,
                ),
                onClick = { context, _ ->
                    val next = !enabled
                    if (next) entry.feature.applyToggle(true)
                    entry.setEnabled(talker, next)
                    showToast(
                        context,
                        localizedChatString(
                            if (next) R.string.bot_chat_menu_group_on_toast
                            else R.string.bot_chat_menu_group_off_toast,
                            name,
                        ),
                    )
                },
            )
        }
    }

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(menuProvider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(menuProvider)
    }
}
