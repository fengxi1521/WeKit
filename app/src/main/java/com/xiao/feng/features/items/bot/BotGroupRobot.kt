package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.reader
import kotlin.io.path.writer

/**
 * 内置脚本「群娱乐机器人」：积分、打劫、婚恋、拍卖、点歌等群玩法。
 *
 * 点击条目打开使用说明，并可直接设置主人 wxid 与生效群；脚本从同一个
 * `config.prop` 读取这些配置。
 */
object BotGroupRobot : ClickableFeature() {

    const val SCRIPT_ID = "bot-group-robot"
    const val CONFIG_ROOT_WXID = "root_wxid"
    const val CONFIG_EFFECTIVE_GROUPS = "allow_group_list"

    override val technicalId = "机器人 · 群娱乐机器人"
    override val nameRes = R.string.feature_bot_group_robot_name
    override val categoryIds = listOf(FeatureCategoryIds.BOT)
    override val descriptionRes = R.string.feature_bot_group_robot_description
    override val displayOrder = 11

    override fun onClick(context: ComponentActivity) {
        showBotGroupRobotDialog(context)
    }

    override fun onEnable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, true)
    }

    override fun onDisable() {
        BundledBotRuntime.setScriptEnabled(SCRIPT_ID, false)
    }

    fun rootWxid(): String = loadConfig().getProperty(CONFIG_ROOT_WXID, "").trim()

    fun effectiveGroups(): Set<String> = loadConfig()
        .getProperty(CONFIG_EFFECTIVE_GROUPS, "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun saveRootWxid(value: String) = updateConfig { setProperty(CONFIG_ROOT_WXID, value.trim()) }

    fun saveEffectiveGroups(groupIds: Set<String>) = updateConfig {
        setProperty(CONFIG_EFFECTIVE_GROUPS, groupIds.joinToString(","))
    }

    private fun loadConfig(): Properties {
        val props = Properties()
        val file = BundledBotRuntime.scriptDir(SCRIPT_ID) / "config.prop"
        if (file.exists()) file.reader(Charsets.UTF_8).use { props.load(it) }
        return props
    }

    private fun updateConfig(mutate: Properties.() -> Unit) {
        val props = loadConfig().apply(mutate)
        val file = BundledBotRuntime.scriptDir(SCRIPT_ID) / "config.prop"
        file.writer(Charsets.UTF_8).use { props.store(it, null) }
    }
}
