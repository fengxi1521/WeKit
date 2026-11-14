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
 * The wake lock and the root commands run against the host context directly. The foreground
 * service lives in the module APK, so it is only available when the module is installed as an
 * app (LSPosed mode); in Zygisk-only mode the module context cannot be created and that part is
 * skipped with a log entry.
 */
object WeChatKeepAliveRuntime {

    private const val TAG = "WeChatKeepAlive"
    private const val WAKE_LOCK_TAG = "XiaoFeng:WeChatKeepAlive"
    private const val HEARTBEAT_URL = "https://connectivitycheck.gstatic.com/generate_204"
    private const val HEARTBEAT_TIMEOUT_MS = 3000

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var rootWhitelistApplied = false

    @Volatile
    private var rootAppOpsApplied = false

    fun start() {
        val host = HostInfo.application
        if (WeChatKeepAlive.wakeLock) acquireWakeLock(host) else releaseWakeLock()

        val module = moduleContext(host)
        if (WeChatKeepAlive.foregroundService) {
            if (module != null) {
                startService(module, WeChatKeepAlive.watchdog, WeChatKeepAlive.networkHeartbeat)
            } else {
                WeLogger.w(TAG, "module app is not installed; skipping the foreground service")
            }
        } else if (module != null) {
            stopService(module)
        }

        if (WeChatKeepAlive.rootDozeWhitelist) applyRootDozeWhitelistOnce()
        if (WeChatKeepAlive.rootAppOps) applyRootAppOpsOnce()
    }

    fun stop() {
        releaseWakeLock()
        moduleContext(HostInfo.application)?.let(::stopService)
    }

    /**
     * The service lives in the module package, so a direct start from WeChat's UID is rejected
     * because the service is not exported. Root can start it anyway; without root the direct
     * attempt is still made first in case the platform allows it.
     */
    private fun startService(module: Context, watchdog: Boolean, heartbeat: Boolean) {
        val direct = runCatching {
            WeChatKeepAliveService.start(module, watchdog, heartbeat)
        }
        if (direct.isSuccess) return
        WeLogger.w(TAG, "direct foreground-service start was rejected; retrying through root", direct.exceptionOrNull())
        Thread {
            runRootCommand(
                "am start-foreground-service -n ${PackageNames.MODULE}/.features.items.system.keepalive.WeChatKeepAliveService" +
                        " --ez ${WeChatKeepAliveService.EXTRA_WATCHDOG} $watchdog" +
                        " --ez ${WeChatKeepAliveService.EXTRA_NETWORK_HEARTBEAT} $heartbeat"
            )
        }.apply {
            name = "XiaoFeng-KeepAlive-ServiceStart"
            isDaemon = true
        }.start()
    }

    private fun stopService(module: Context) {
        val stopped = runCatching { WeChatKeepAliveService.stop(module) }.getOrDefault(false)
        if (stopped) return
        Thread {
            runRootCommand(
                "am stop-service -n ${PackageNames.MODULE}/.features.items.system.keepalive.WeChatKeepAliveService"
            )
        }.apply {
            name = "XiaoFeng-KeepAlive-ServiceStop"
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

    private fun moduleContext(host: Context): Context? =
        runCatching {
            host.createPackageContext(PackageNames.MODULE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()

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

    private fun applyRootDozeWhitelistOnce() {
        if (rootWhitelistApplied) return
        rootWhitelistApplied = true
        Thread {
            if (!runRootCommand("cmd deviceidle whitelist +${PackageNames.WECHAT}")) {
                WeLogger.e(TAG, "failed to add WeChat to the Doze whitelist")
            }
        }.apply {
            name = "XiaoFeng-KeepAlive-RootWhitelist"
            isDaemon = true
        }.start()
    }

    private fun applyRootAppOpsOnce() {
        if (rootAppOpsApplied) return
        rootAppOpsApplied = true
        Thread {
            val commands = listOf(
                "cmd appops set ${PackageNames.WECHAT} RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set ${PackageNames.WECHAT} RUN_IN_BACKGROUND allow",
                "cmd appops set ${PackageNames.WECHAT} WAKE_LOCK allow",
                "cmd appops set ${PackageNames.WECHAT} START_FOREGROUND allow",
            )
            if (!commands.all(::runRootCommand)) {
                WeLogger.e(TAG, "failed to allow WeChat background AppOps")
            }
        }.apply {
            name = "XiaoFeng-KeepAlive-RootAppOps"
            isDaemon = true
        }.start()
    }

    private fun runRootCommand(command: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        process.waitFor() == 0
    }.getOrDefault(false)

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
