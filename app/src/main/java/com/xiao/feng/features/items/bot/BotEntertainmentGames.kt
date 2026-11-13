package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds

/**
 * 内置脚本「娱乐游戏」：成语接龙与猜人物。
 *
 * 脚本随 APK 打包在 `assets/bot_scripts/bot-entertainment-games/`，开关直接控制该脚本的
 * 加载状态，不需要启用「脚本引擎 (Java)」。点击条目可查看完整指令说明。
 */
object BotEntertainmentGames : ClickableFeature() {

    const val SCRIPT_ID = "bot-entertainment-games"

    override val technicalId = "机器人 · 娱乐游戏"
    override val nameRes = R.string.feature_bot_games_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_games_description
    override val displayOrder = 10

    override fun onClick(context: ComponentActivity) {
        showBotGamesDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, false)
    }
}
