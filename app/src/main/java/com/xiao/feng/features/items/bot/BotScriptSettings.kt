package com.xiao.feng.features.items.bot

import java.util.Properties
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.reader
import kotlin.io.path.writer

/**
 * 内置机器人脚本的通用配置读写。脚本与设置界面通过脚本目录下的 `config.prop` 共享配置。
 */
object BotScriptSettings {

    const val CONFIG_EFFECTIVE_GROUPS = "allow_group_list"

    fun effectiveGroups(scriptId: String): Set<String> = loadConfig(scriptId)
        .getProperty(CONFIG_EFFECTIVE_GROUPS, "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun saveEffectiveGroups(scriptId: String, groupIds: Set<String>) {
        updateConfig(scriptId) {
            setProperty(CONFIG_EFFECTIVE_GROUPS, groupIds.joinToString(","))
        }
    }

    private fun loadConfig(scriptId: String): Properties {
        val props = Properties()
        val file = BundledBotRuntime.scriptDir(scriptId) / "config.prop"
        if (file.exists()) file.reader(Charsets.UTF_8).use { props.load(it) }
        return props
    }

    private fun updateConfig(scriptId: String, mutate: Properties.() -> Unit) {
        val props = loadConfig(scriptId).apply(mutate)
        val file = BundledBotRuntime.scriptDir(scriptId) / "config.prop"
        file.writer(Charsets.UTF_8).use { props.store(it, null) }
    }
}
