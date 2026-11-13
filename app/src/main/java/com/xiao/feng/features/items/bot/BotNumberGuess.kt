package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds

/**
 * 内置脚本「猜数字小游戏」：群内发送「猜50」猜 1~100 的数字。
 *
 * 点击条目可查看玩法并勾选生效群。
 */
object BotNumberGuess : ClickableFeature() {

    const val SCRIPT_ID = "bot-number-guess"

    override val technicalId = "机器人 · 猜数字"
    override val nameRes = R.string.feature_bot_number_guess_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_number_guess_description
    override val displayOrder = 13

    override fun onClick(context: ComponentActivity) {
        showBotNumberGuessDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, false)
    }
}
