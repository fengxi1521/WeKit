package com.xiao.feng.features.items.system.keepalive

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.xiao.feng.constants.PackageNames
import com.xiao.feng.utils.WeLogger
import java.util.concurrent.Executors

/**
 * Applies the keep-alive root commands from the module app's own UID.
 *
 * WeChat is not (and should not be) granted root, so `su` started inside the host process is
 * rejected and the whitelists never apply. The host binds here instead; only callers whose UID
 * belongs to WeChat are accepted. The module process also owns the foreground service, so it is
 * started from here rather than from the host process, where the non-exported service start is
 * rejected.
 */
class KeepAliveRootService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                MSG_SYNC -> {
                    handleSync(message)
                    true
                }

                MSG_STOP -> {
                    executor.execute(::stopForegroundService)
                    true
                }

                else -> false
            }
        },
    )

    override fun onBind(intent: Intent?): IBinder? {
        val caller = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(caller)
        if (packages?.contains(PackageNames.WECHAT) != true) {
            WeLogger.w(TAG, "rejected keep-alive request from uid $caller")
            return null
        }
        return messenger.binder
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleSync(message: Message) {
        val data = message.data
        val doze = data.getBoolean(KEY_DOZE)
        val appOps = data.getBoolean(KEY_APP_OPS)
        // 模块主页面上的开关优先: 用户在模块 App 里配置后, 微信进程同步上来的旧值不能把它覆盖掉。
        val modulePrefs = WeChatKeepAliveService.prefs(this)
        val moduleOwned = modulePrefs.getBoolean(WeChatKeepAliveService.KEY_MODULE_OWNED, false)
        val foregroundService = moduleOwned || data.getBoolean(KEY_FOREGROUND_SERVICE)
        val watchdog = if (moduleOwned) {
            modulePrefs.getBoolean(WeChatKeepAliveService.KEY_WATCHDOG, true)
        } else {
            data.getBoolean(KEY_WATCHDOG)
        }
        val heartbeat = if (moduleOwned) {
            modulePrefs.getBoolean(WeChatKeepAliveService.KEY_NETWORK_HEARTBEAT, true)
        } else {
            data.getBoolean(KEY_HEARTBEAT)
        }
        val reply = message.replyTo
        executor.execute {
            val status = KeepAliveRootCommands.applyAndProbe(doze, appOps)
            if (foregroundService) {
                startForegroundService(watchdog, heartbeat)
            } else {
                stopForegroundService()
            }
            if (reply == null) return@execute
            val result = Bundle().apply {
                putBoolean(KEY_ROOT, status.rootAvailable)
                putBoolean(KEY_DOZE, status.dozeWhitelisted)
                putBoolean(KEY_APP_OPS, status.backgroundAllowed)
            }
            runCatching { reply.send(Message.obtain(null, MSG_SYNC).apply { this.data = result }) }
        }
    }

    /**
     * This process is in the background, so a direct foreground-service start may hit the Android
     * 12+ background-start restriction; the module's own root is the fallback.
     */
    private fun startForegroundService(watchdog: Boolean, heartbeat: Boolean) {
        val direct = runCatching { WeChatKeepAliveService.start(this, watchdog, heartbeat) }
        if (direct.isSuccess) return
        KeepAliveRootCommands.run(
            "am start-foreground-service -n ${PackageNames.MODULE}/${WeChatKeepAliveService::class.java.name}" +
                    " --ez ${WeChatKeepAliveService.EXTRA_WATCHDOG} $watchdog" +
                    " --ez ${WeChatKeepAliveService.EXTRA_NETWORK_HEARTBEAT} $heartbeat",
        )
    }

    private fun stopForegroundService() {
        val stopped = runCatching { WeChatKeepAliveService.stop(this) }.getOrDefault(false)
        if (stopped) return
        KeepAliveRootCommands.run(
            "am stop-service -n ${PackageNames.MODULE}/${WeChatKeepAliveService::class.java.name}",
        )
    }

    companion object {
        private const val TAG = "KeepAliveRootService"

        const val MSG_SYNC = 1
        const val MSG_STOP = 2

        const val KEY_ROOT = "root"
        const val KEY_DOZE = "doze"
        const val KEY_APP_OPS = "appOps"
        const val KEY_FOREGROUND_SERVICE = "foregroundService"
        const val KEY_WATCHDOG = "watchdog"
        const val KEY_HEARTBEAT = "heartbeat"
    }
}
