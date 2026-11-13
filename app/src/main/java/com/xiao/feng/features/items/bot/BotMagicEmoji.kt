package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds

/**
 * 内置脚本「魔法表情」：引用消息后发送两字指令，用对方头像生成动画表情。
 *
 * 点击条目可查看指令并勾选生效群。
 */
object BotMagicEmoji : ClickableFeature() {

    const val SCRIPT_ID = "bot-magic-emoji"

    override val technicalId = "机器人 · 魔法表情"
    override val nameRes = R.string.feature_bot_magic_emoji_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_magic_emoji_description
    override val displayOrder = 15

    override fun onClick(context: ComponentActivity) {
        showBotMagicEmojiDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, false)
    }
}
