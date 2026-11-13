package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 内置脚本「娱乐菜单系统」：宠物、钓鱼、天气、点歌等群小游戏。
 *
 * 点击条目可查看首次初始化步骤与常用玩法。
 */
object BotMoinMenu : ClickableFeature() {

    const val SCRIPT_ID = "bot-moin-menu"

    override val technicalId = "机器人 · 娱乐菜单系统"
    override val nameRes = R.string.feature_bot_moin_menu_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_moin_menu_description
    override val displayOrder = 12

    override fun onClick(context: ComponentActivity) {
        showBotMoinMenuDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, false)
    }

    /** 当前群是否开启了娱乐菜单的聊天功能。 */
    fun isGroupEnabled(groupId: String): Boolean = readGroupConfig()[groupId] == "1"

    /** 长按加号菜单用的快捷开关，同时打开脚本总开关，避免还要去设置页点一次。 */
    fun setGroupEnabled(groupId: String, enabled: Boolean) {
        val dir = BundledBotRuntime.scriptDir(SCRIPT_ID)
        if (enabled) dir.resolve("chat_config.dat").writeText("1")
        val config = readGroupConfig().toMutableMap()
        config[groupId] = if (enabled) "1" else "0"
        dir.resolve("group_config.dat")
            .writeText(config.entries.joinToString("") { "${it.key}=${it.value}|" })
    }

    private fun readGroupConfig(): Map<String, String> {
        val file = BundledBotRuntime.scriptDir(SCRIPT_ID) / "group_config.dat"
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readText().split('|')
                .mapNotNull { pair ->
                    val index = pair.indexOf('=')
                    if (index <= 0) null
                    else pair.substring(0, index) to pair.substring(index + 1)
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }
}
