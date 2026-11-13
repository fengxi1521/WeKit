package com.xiao.feng.features.items.bot

import java.util.Properties
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.reader
import kotlin.io.path.writer

/**
 * 内置机器人脚本的通用配置读写。脚本与设置界面通过脚本目录下的 `config.prop` 共享配置。
 *
 * - `allow_group_list`: 生效群白名单，为空表示所有群生效。
 * - `disabled_group_list`: 单独关闭的群，优先级高于白名单，供长按加号菜单快速开关使用。
 */
object BotScriptSettings {

    const val CONFIG_EFFECTIVE_GROUPS = "allow_group_list"
    const val CONFIG_DISABLED_GROUPS = "disabled_group_list"

    fun effectiveGroups(scriptId: String): Set<String> =
        loadConfig(scriptId).groupSet(CONFIG_EFFECTIVE_GROUPS)

    fun disabledGroups(scriptId: String): Set<String> =
        loadConfig(scriptId).groupSet(CONFIG_DISABLED_GROUPS)

    fun saveEffectiveGroups(scriptId: String, groupIds: Set<String>) {
        updateConfig(scriptId) {
            setProperty(CONFIG_EFFECTIVE_GROUPS, groupIds.joinToString(","))
        }
    }

    /** 当前群是否对脚本生效：不在关闭名单，且白名单为空或包含该群。 */
    fun isGroupEnabled(scriptId: String, groupId: String): Boolean {
        if (groupId in disabledGroups(scriptId)) return false
        val allowed = effectiveGroups(scriptId)
        return allowed.isEmpty() || groupId in allowed
    }

    /**
     * 从长按加号菜单切换某个群：打开时移出关闭名单，并在白名单非空时补进白名单。
     */
    fun setGroupEnabled(scriptId: String, groupId: String, enabled: Boolean) {
        updateConfig(scriptId) {
            val disabled = groupSet(CONFIG_DISABLED_GROUPS).toMutableSet()
            val allowed = groupSet(CONFIG_EFFECTIVE_GROUPS).toMutableSet()
            if (enabled) {
                disabled.remove(groupId)
                if (allowed.isNotEmpty()) allowed.add(groupId)
            } else {
                disabled.add(groupId)
            }
            setProperty(CONFIG_DISABLED_GROUPS, disabled.joinToString(","))
            if (allowed.isNotEmpty() || getProperty(CONFIG_EFFECTIVE_GROUPS) != null) {
                setProperty(CONFIG_EFFECTIVE_GROUPS, allowed.joinToString(","))
            }
        }
    }

    private fun Properties.groupSet(key: String): Set<String> = getProperty(key, "")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

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
