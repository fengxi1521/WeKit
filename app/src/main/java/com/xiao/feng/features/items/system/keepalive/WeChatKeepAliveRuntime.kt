package com.xiao.feng.features.items.system.keepalive

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.xiao.feng.constants.PackageNames
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger

/**
 * Applies the 微信强保活 options from the WeChat process.
 *
 * The wake lock runs against the host context directly. Root commands and the foreground service
 * need the module app's UID: WeChat is normally not granted root, so the host forwards those to
 * [KeepAliveRootService] in the module app (which the user does grant root). In Zygisk-only mode
 * the module app does not exist and that part is skipped with a log entry.
 */
object WeChatKeepAliveRuntime {

    private const val TAG = "WeChatKeepAlive"
    private const val WAKE_LOCK_TAG = "XiaoFeng:WeChatKeepAlive"
    private const val HEARTBEAT_URL = "https://connectivitycheck.gstatic.com/generate_204"
    private const val HEARTBEAT_TIMEOUT_MS = 3000

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var moduleSyncInFlight = false

    fun start() {
        val host = HostInfo.application
        if (WeChatKeepAlive.wakeLock) acquireWakeLock(host) else releaseWakeLock()
        syncWithModule()
    }

    fun stop() {
        releaseWakeLock()
        Thread {
            runCatching { KeepAliveRootClient.stop() }
        }.apply {
            name = "XiaoFeng-KeepAlive-ModuleStop"
            isDaemon = true
        }.start()
    }

    /**
     * Re-applies the enabled root whitelists and reads the result back from the system. The
     * settings dialog calls this when it opens and when the status row is tapped, so "did root
     * work" becomes something the UI shows instead of something the user has to guess.
     */
    fun reapplyAndVerifyRootOptions(): RootStatus =
        KeepAliveRootClient.sync(
            WeChatKeepAlive.rootDozeWhitelist,
            WeChatKeepAlive.rootAppOps,
            WeChatKeepAlive.foregroundService,
            WeChatKeepAlive.watchdog,
            WeChatKeepAlive.networkHeartbeat,
        ) ?: KeepAliveRootCommands.applyAndProbe(
            WeChatKeepAlive.rootDozeWhitelist,
            WeChatKeepAlive.rootAppOps,
        )

    /**
     * The module app is the UID the user granted root to and it owns the foreground service, so it
     * applies everything. `su` in the host process is only a fallback for Zygisk-only installs
     * where the module app does not exist and the host itself has root.
     */
    private fun syncWithModule() {
        if (moduleSyncInFlight) return
        moduleSyncInFlight = true
        Thread {
            try {
                val synced = KeepAliveRootClient.sync(
                    WeChatKeepAlive.rootDozeWhitelist,
                    WeChatKeepAlive.rootAppOps,
                    WeChatKeepAlive.foregroundService,
                    WeChatKeepAlive.watchdog,
                    WeChatKeepAlive.networkHeartbeat,
                )
                if (synced != null) return@Thread

                if (WeChatKeepAlive.rootDozeWhitelist &&
                    !KeepAliveRootCommands.applyDozeWhitelist()
                ) {
                    WeLogger.e(TAG, "module app is unavailable and the host has no root")
                }
                if (WeChatKeepAlive.rootAppOps && !KeepAliveRootCommands.applyBackgroundAppOps()) {
                    WeLogger.e(TAG, "module app is unavailable and the host has no root")
                }
                if (WeChatKeepAlive.foregroundService && !KeepAliveRootCommands.run(
                        "am start-foreground-service -n ${PackageNames.MODULE}/${WeChatKeepAliveService::class.java.name}" +
                                " --ez ${WeChatKeepAliveService.EXTRA_WATCHDOG} ${WeChatKeepAlive.watchdog}" +
                                " --ez ${WeChatKeepAliveService.EXTRA_NETWORK_HEARTBEAT} ${WeChatKeepAlive.networkHeartbeat}",
                    )
                ) {
                    WeLogger.w(TAG, "module app is not installed; the foreground service was not started")
                }
            } finally {
                moduleSyncInFlight = false
            }
        }.apply {
            name = "XiaoFeng-KeepAlive-ModuleSync"
            isDaemon = true
        }.start()
    }

    /** Called once per watchdog interval by the module's foreground service. */
    fun watchdogTick(context: Context, enabled: Boolean) {
        if (!enabled) return
        launchWeChatIfNeeded(context)
    }

    /** Called once per watchdog interval by the module's foreground service. */
    fun heartbeatTick(enabled: Boolean) {
        if (!enabled) return
        Thread {
            runCatching {
                val connection = java.net.URL(HEARTBEAT_URL).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = HEARTBEAT_TIMEOUT_MS
                connection.readTimeout = HEARTBEAT_TIMEOUT_MS
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.responseCode
                connection.disconnect()
            }.onFailure { WeLogger.e(TAG, "network heartbeat failed", it) }
        }.apply {
            name = "XiaoFeng-KeepAlive-Heartbeat"
            isDaemon = true
        }.start()
    }

    private fun acquireWakeLock(context: Context) {
        val current = wakeLock
        if (current?.isHeld == true) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun launchWeChatIfNeeded(context: Context) {
        runCatching {
            if (isWeChatProcessAlive()) return
            val intent = context.packageManager.getLaunchIntentForPackage(PackageNames.WECHAT) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { WeLogger.e(TAG, "failed to relaunch WeChat", it) }
    }

    private fun isWeChatProcessAlive(): Boolean = runCatching {
        val process = ProcessBuilder("sh", "-c", "pidof ${PackageNames.WECHAT}")
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    }.getOrDefault(false)
}
