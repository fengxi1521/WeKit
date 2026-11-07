package com.xiao.feng.features.items.bot

import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

/**
 * 内置脚本「群娱乐机器人」：积分、打劫、婚恋、拍卖、点歌等群玩法。
 */
object BotGroupRobot : SwitchFeature() {

    override val technicalId = "机器人 · 群娱乐机器人"
    override val nameRes = R.string.feature_bot_group_robot_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_group_robot_description
    override val displayOrder = 11

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled("bot-group-robot", true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled("bot-group-robot", false)
    }
}
