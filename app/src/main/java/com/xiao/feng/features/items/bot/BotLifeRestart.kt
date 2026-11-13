package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds

/**
 * 内置脚本「人生重开模拟器」：随机天赋、人生事件与排行榜。
 *
 * 点击条目可查看玩法并勾选生效群。
 */
object BotLifeRestart : ClickableFeature() {

    const val SCRIPT_ID = "bot-life-restart"

    override val technicalId = "机器人 · 人生重开"
    override val nameRes = R.string.feature_bot_life_restart_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_life_restart_description
    override val displayOrder = 14

    override fun onClick(context: ComponentActivity) {
        showBotLifeRestartDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, false)
    }
}
