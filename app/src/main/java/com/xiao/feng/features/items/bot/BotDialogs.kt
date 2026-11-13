package com.xiao.feng.features.items.bot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeGroup
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.content.m3.lazySegmentedItems
import com.xiao.feng.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GROUP_ROBOT_USAGE = """
群娱乐机器人已内置，不需要启用「脚本引擎 (Java)」，打开开关后直接在生效群里发指令即可。

常用指令：
· 菜单 / 帮助 —— 查看完整玩法
· 签到、人品、情话、讲笑话、算命、买运气、开盲盒、赎身
· 打劫@某人、积分榜、活跃榜
· 结婚@某人 → 对方发送「同意求婚」或「同意结婚」
· 离婚、送礼@某人 金额
· 拍卖 底价 → 出价@某人 金额
· 点歌 歌名、领养宝宝、喂食宝宝、宝宝状态
· 我的微信 id —— 查看自己的 wxid
· 设置生效群 / 添加生效群 / 移除生效群 / 查看生效群（仅主人）
""".trimIndent()

private val GAMES_USAGE = """
娱乐游戏已内置，不需要启用「脚本引擎 (Java)」。

在聊天或群里发送：
· #成语接龙 —— 开始成语接龙
· #猜人物 —— 开始猜人物（题库随 APK 内置）
· #提示 —— 获取当前题目提示
· #下一题 —— 跳过当前猜人物题
· #排行榜 —— 查看本局排行
· #猜人物总排行 —— 查看总排行榜
· #结束 —— 结束当前游戏
· #帮助 —— 查看完整帮助

猜人物答对 3/5 题时会 @ 提醒，并奖励提示与跳题次数。
""".trimIndent()

private val MOIN_USAGE = """
娱乐菜单系统已内置，不需要启用「脚本引擎 (Java)」。

首次使用请先在群里发送：
#设置管理员 账号 密码
（第一个发送的人会成为管理员，之后发送「菜单」或「帮助」查看完整玩法）

常用功能：
· 签到、我的资料、排行榜、每日任务
· 我的宠物、宠物商店、我的道具、道具商城
· 钓鱼、打怪、回血、打工、PK、大富翁、盲盒、开宝箱
· 热门语录、今日新闻、天气 城市、点歌 歌名
· 人品、抽签、配对、求财神、好友列表、讲笑话
· 定时 HH:MM / 取消定时任务 / 查看定时任务
· #我的id —— 查看 wxid 与快捷 ID
""".trimIndent()

fun showBotGroupRobotDialog(activity: ComponentActivity) {
    showComposeDialog(activity) {
        var rootWxid by remember { mutableStateOf(BotGroupRobot.rootWxid()) }
        var effectiveGroups by remember { mutableStateOf(BotGroupRobot.effectiveGroups()) }
        val groups by produceState<List<WeGroup>>(initialValue = emptyList()) {
            value = runCatching {
                withContext(Dispatchers.IO) { WeDatabaseApi.getGroups() }
            }.getOrDefault(emptyList())
        }

        AlertDialogContent(
            title = { Text("群娱乐机器人 · 使用说明与设置") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                ) {
                    item {
                        Text(GROUP_ROBOT_USAGE, style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        Text(
                            "设置",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "主人 wxid",
                            value = rootWxid,
                            onValueChange = {
                                rootWxid = it
                                BotGroupRobot.saveRootWxid(it)
                            },
                            dialogTitle = "设置主人 wxid",
                            confirmLabel = "保存",
                            dismissLabel = "取消",
                            valueHint = "留空则使用当前登录账号",
                        )
                    }
                    item {
                        Text(
                            "生效群（不勾选则所有群生效）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (groups.isEmpty()) {
                        item {
                            Text("没有读取到群聊列表，可先用「设置生效群」指令配置。")
                        }
                    }
                    lazySegmentedItems(groups, key = { it.wxId }) { group ->
                        SwitchWidget(
                            title = group.nickname.ifBlank { group.wxId },
                            description = group.wxId,
                            checked = group.wxId in effectiveGroups,
                            onCheckedChange = { checked ->
                                val updated = if (checked) {
                                    effectiveGroups + group.wxId
                                } else {
                                    effectiveGroups - group.wxId
                                }
                                effectiveGroups = updated
                                BotGroupRobot.saveEffectiveGroups(updated)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )
    }
}

fun showBotGamesDialog(activity: ComponentActivity) {
    showBotUsageDialog(activity, "娱乐游戏 · 使用说明", GAMES_USAGE)
}

fun showBotMoinMenuDialog(activity: ComponentActivity) {
    showBotUsageDialog(activity, "娱乐菜单系统 · 使用说明", MOIN_USAGE)
}

private val NUMBER_GUESS_USAGE = """
猜数字小游戏已内置，不需要启用「脚本引擎 (Java)」。

在生效群里发送：
· 游戏菜单 —— 查看玩法
· 猜50 —— 猜 1~100 的数字，会提示偏大或偏小
· 重置数字 —— 重新生成答案（管理员）
· 游戏开关 —— 开启或关闭本群游戏（管理员）

管理员默认为当前登录账号。
""".trimIndent()

private val LIFE_RESTART_USAGE = """
人生重开模拟器已内置，不需要启用「脚本引擎 (Java)」。

在生效群里发送：
· 人生开关 —— 开启或关闭游戏（管理员）
· 开始模拟 —— 开始新的一生
· 游戏帮助 —— 查看完整玩法
· 人生排行榜 —— 查看 TOP10

开局从 6 个随机天赋中选择 3 个，之后每年从人生选项中选择一条路。
勾选生效群后会自动视为已开启，无需再发送「人生开关」。
""".trimIndent()

private val MAGIC_EMOJI_USAGE = """
魔法表情已内置，不需要启用「脚本引擎 (Java)」，也不依赖外部接口。

用法：长按对方消息 → 引用 → 发送两字指令。

可用指令：
摸摸头、挠头、亲亲、爱心、大佬、拽哥
生气、捶你、威胁、打死、去玩、射
飞机、来了、踢飞、打球、摇乐、呼圈
扭屁、摇起、按脚、口我、吃了、需要、咸鱼

发送「魔法菜单」可随时查看指令列表。
""".trimIndent()

fun showBotNumberGuessDialog(activity: ComponentActivity) {
    showBotScriptSettingsDialog(
        activity,
        "猜数字小游戏 · 使用说明与设置",
        BotNumberGuess.SCRIPT_ID,
        NUMBER_GUESS_USAGE,
    )
}

fun showBotLifeRestartDialog(activity: ComponentActivity) {
    showBotScriptSettingsDialog(
        activity,
        "人生重开模拟器 · 使用说明与设置",
        BotLifeRestart.SCRIPT_ID,
        LIFE_RESTART_USAGE,
    )
}

fun showBotMagicEmojiDialog(activity: ComponentActivity) {
    showBotScriptSettingsDialog(
        activity,
        "魔法表情 · 使用说明与设置",
        BotMagicEmoji.SCRIPT_ID,
        MAGIC_EMOJI_USAGE,
    )
}

private fun showBotScriptSettingsDialog(
    activity: ComponentActivity,
    title: String,
    scriptId: String,
    usage: String,
) {
    showComposeDialog(activity) {
        var effectiveGroups by remember { mutableStateOf(BotScriptSettings.effectiveGroups(scriptId)) }
        val groups by produceState<List<WeGroup>>(initialValue = emptyList()) {
            value = runCatching {
                withContext(Dispatchers.IO) { WeDatabaseApi.getGroups() }
            }.getOrDefault(emptyList())
        }

        AlertDialogContent(
            title = { Text(title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                ) {
                    item { Text(usage, style = MaterialTheme.typography.bodyMedium) }
                    item {
                        Text(
                            "生效群（不勾选则所有群生效）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (groups.isEmpty()) {
                        item {
                            Text("没有读取到群聊列表，请确认微信已登录并有群聊。")
                        }
                    }
                    lazySegmentedItems(groups, key = { it.wxId }) { group ->
                        SwitchWidget(
                            title = group.nickname.ifBlank { group.wxId },
                            description = group.wxId,
                            checked = group.wxId in effectiveGroups,
                            onCheckedChange = { checked ->
                                val updated = if (checked) {
                                    effectiveGroups + group.wxId
                                } else {
                                    effectiveGroups - group.wxId
                                }
                                effectiveGroups = updated
                                BotScriptSettings.saveEffectiveGroups(scriptId, updated)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )
    }
}

private fun showBotUsageDialog(activity: ComponentActivity, title: String, usage: String) {
    showComposeDialog(activity) {
        AlertDialogContent(
            title = { Text(title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                ) {
                    item { Text(usage, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
        )
    }
}
