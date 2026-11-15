package com.xiao.feng.features.items.system.keepalive

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds

/**
 * Ported from Hchat's 微信强保活: a foreground service, a partial wake lock and optional root
 * whitelists that raise the odds of the WeChat process surviving with the screen off, so the
 * automation features (red packets, mention replies, scheduled messages) keep running.
 */
object WeChatKeepAlive : ClickableFeature() {

    override val technicalId = "微信强保活"
    override val nameRes = R.string.feature_wechat_keep_alive_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_wechat_keep_alive_description

    private const val KEY_FOREGROUND_SERVICE = "wechat_keep_alive_foreground_service"
    private const val KEY_WAKE_LOCK = "wechat_keep_alive_wake_lock"
    private const val KEY_WATCHDOG = "wechat_keep_alive_watchdog"
    private const val KEY_NETWORK_HEARTBEAT = "wechat_keep_alive_network_heartbeat"
    private const val KEY_ROOT_DOZE_WHITELIST = "wechat_keep_alive_root_doze_whitelist"
    private const val KEY_ROOT_APP_OPS = "wechat_keep_alive_root_app_ops"
    private const val KEY_ROOT_DEFAULTS_MIGRATED = "wechat_keep_alive_root_defaults_migrated_v1"

    var foregroundService: Boolean by prefOption(KEY_FOREGROUND_SERVICE, true)
    var wakeLock: Boolean by prefOption(KEY_WAKE_LOCK, true)
    var watchdog: Boolean by prefOption(KEY_WATCHDOG, false)
    var networkHeartbeat: Boolean by prefOption(KEY_NETWORK_HEARTBEAT, false)
    var rootDozeWhitelist: Boolean by prefOption(KEY_ROOT_DOZE_WHITELIST, true)
    var rootAppOps: Boolean by prefOption(KEY_ROOT_APP_OPS, true)

    override fun onEnable() {
        migrateRootDefaultsOnce()
        WeChatKeepAliveRuntime.start()
    }

    override fun onDisable() = WeChatKeepAliveRuntime.stop()

    /**
     * Background automations (red packets, mention replies, music orders) stop working the moment
     * the host is frozen, so they turn keep-alive on themselves instead of relying on the user.
     */
    fun ensureEnabled() {
        if (isEnabled) return
        KvStore.putBool(technicalId, true)
        isEnabled = true
    }

    /**
     * Root whitelists used to default to off, which left doze free to cut the network. Enable them
     * once for existing installs; afterwards the user's own choice is respected.
     */
    private fun migrateRootDefaultsOnce() {
        if (KvStore.getBoolOrDef(KEY_ROOT_DEFAULTS_MIGRATED, false)) return
        if (!rootDozeWhitelist) rootDozeWhitelist = true
        if (!rootAppOps) rootAppOps = true
        KvStore.putBool(KEY_ROOT_DEFAULTS_MIGRATED, true)
    }

    override fun onClick(context: ComponentActivity) = showWeChatKeepAliveDialog(context)
}
