package com.xiao.feng.features.items.bot

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Extension
import com.xiao.feng.R
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.chat.localizedChatString
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
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
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "bot_toggle_menu",
                icon = MaterialSymbols.Outlined.Extension,
                label = localizedChatString(R.string.bot_chat_menu_title),
                onClick = { context, _ -> showBotToggleDialog(context, talker) },
            )
        )
    }

    /** 六个内置机器人的本群开关收进同一个二级弹窗, 点开「机器人」后才显示。 */
    private fun showBotToggleDialog(context: Context, talker: String) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.bot_chat_menu_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        entries.forEach { entry ->
                            item(key = entry.scriptId) {
                                val name = stringResource(entry.nameRes)
                                val enabled = remember(revision) {
                                    entry.feature.isEnabled && entry.isEnabled(talker)
                                }
                                SwitchWidget(
                                    title = name,
                                    description = stringResource(
                                        if (enabled) R.string.bot_chat_menu_group_on
                                        else R.string.bot_chat_menu_group_off,
                                        name,
                                    ),
                                    checked = enabled,
                                    onCheckedChange = { next ->
                                        if (next) entry.feature.applyToggle(true)
                                        entry.setEnabled(talker, next)
                                        revision++
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
                    }
                },
                confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
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
