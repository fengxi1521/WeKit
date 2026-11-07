package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds

/**
 * 内置脚本「娱乐菜单系统」：宠物、钓鱼、天气、点歌等群小游戏。
 *
 * 点击条目可查看首次初始化步骤与常用玩法。
 */
object BotMoinMenu : ClickableFeature() {

    override val technicalId = "机器人 · 娱乐菜单系统"
    override val nameRes = R.string.feature_bot_moin_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_moin_menu_description
    override val displayOrder = 12

    override fun onClick(context: ComponentActivity) {
        showBotMoinMenuDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled("bot-moin-menu", true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled("bot-moin-menu", false)
    }
}
