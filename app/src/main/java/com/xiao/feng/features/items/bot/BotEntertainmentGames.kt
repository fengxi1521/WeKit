package com.xiao.feng.features.items.bot

import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

/**
 * 内置脚本「娱乐游戏」：成语接龙与猜人物。
 *
 * 脚本随 APK 打包在 `assets/bot_scripts/bot-entertainment-games/`，开关直接控制该脚本的
 * 加载状态，不需要启用「脚本引擎 (Java)」。
 */
object BotEntertainmentGames : SwitchFeature() {

    override val technicalId = "机器人 · 娱乐游戏"
    override val nameRes = R.string.feature_bot_games_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_games_description
    override val displayOrder = 10

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled("bot-entertainment-games", true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled("bot-entertainment-games", false)
    }
}
