package com.xiao.feng.features.items.chat

import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.TargetProcesses

/**
 * 让后台收到的消息推送回到主进程处理。
 *
 * 微信退到后台 (或被从最近任务划掉) 后会进入「通知模式」: 主进程被主动退出, 推送全部交给
 * `:push` 里的 LightPush 只弹一条通知 —— 消息不写库、会话不刷新, 主进程也不会被拉起。
 * 开启后, 在 `:push` 里强制这些推送回落到微信自己的 push-to-main 路径, 新消息会像前台
 * 一样正常落库并刷新会话与通知。
 */
object BackgroundMessageRefresh : SwitchFeature(), IResolveDex {

    override val technicalId = "后台刷新消息"
    override val nameRes = R.string.feature_background_message_refresh_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_background_message_refresh_description

    /** 开关在主进程展示, 真正拦截推送的是 `:push`。 */
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    /**
     * 在设置页切换开关时 `:push` 通常已经活着。若只在启动时按开关决定是否安装钩子, 新设置
     * 要等 `:push` 重启才生效, 所以 `:push` 里始终安装钩子, 开关状态在每次推送时从 KvStore
     * (跨进程, 1 秒缓存) 读取。
     */
    override val shouldEnableOnStartup: Boolean
        get() = super.shouldEnableOnStartup || TargetProcesses.currentType == TargetProcess.PUSH

    /**
     * `com.tencent.mm.booter.m.c()` — 本次推送是否只交给 LightPush 处理 (只弹通知, 不写
     * 消息库, 也不拉起主进程)。返回 false 时宿主回落到 push-to-main。
     */
    private val methodShouldUseLightPush by dexMethod()

    /**
     * `...LightPushServiceImpl.bj(...)` — 逐条消息判断「是否拉起主进程」的策略。返回 true
     * 时宿主取消 LightPush 并拉起主进程。
     */
    private val methodShouldLaunchToMain by dexMethod()

    override fun onEnable() {
        if (TargetProcesses.currentType != TargetProcess.PUSH) return

        methodShouldUseLightPush.hookBefore {
            if (!enabledNow()) return@hookBefore
            result = false
        }
        methodShouldLaunchToMain.hookBefore {
            if (!enabledNow()) return@hookBefore
            result = true
        }
    }

    private fun enabledNow(): Boolean = KvStore.getBoolOrDef(technicalId, defaultEnabled)
}
