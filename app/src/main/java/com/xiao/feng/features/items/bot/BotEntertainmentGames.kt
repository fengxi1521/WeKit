package com.xiao.feng.features.items.bot

import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.scripting_java.JavaScriptingHook

/**
 * 内置脚本「娱乐游戏」：成语接龙与猜人物。
 *
 * 脚本随 APK 打包在 `assets/scripts_java/bot-entertainment-games/`，首次启用时展开到
 * `scripts_java/`。开关直接控制脚本的 `disabled.flag`，并在脚本引擎已运行时即时加载/卸载。
 */
object BotEntertainmentGames : SwitchFeature() {

    override val technicalId = "机器人 · 娱乐游戏"
    override val nameRes = R.string.feature_bot_games_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_games_description
    override val displayOrder = 10

    override fun onEnable() {
        JavaScriptingHook.setBundledScriptEnabled("bot-entertainment-games", true)
    }

    override fun onDisable() {
        JavaScriptingHook.setBundledScriptEnabled("bot-entertainment-games", false)
    }
}
