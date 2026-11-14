package com.xiao.feng.features.items.system.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.xiao.feng.R

/**
 * Low-importance foreground service hosted by the module APK. It only exists to keep a process
 * with a notification alive so the watchdog can relaunch WeChat and the heartbeat can keep the
 * network link active while the host process is gone.
 */
class WeChatKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var watchdogEnabled = false
    private var networkHeartbeatEnabled = false

    private val keepAliveTask = object : Runnable {
        override fun run() {
            WeChatKeepAliveRuntime.watchdogTick(this@WeChatKeepAliveService, watchdogEnabled)
            WeChatKeepAliveRuntime.heartbeatTick(networkHeartbeatEnabled)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        watchdogEnabled = intent?.getBooleanExtra(EXTRA_WATCHDOG, false) == true
        networkHeartbeatEnabled = intent?.getBooleanExtra(EXTRA_NETWORK_HEARTBEAT, false) == true
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.removeCallbacks(keepAliveTask)
        handler.postDelayed(keepAliveTask, WATCHDOG_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveTask)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wechat_keep_alive_service_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.wechat_keep_alive_service_title))
            .setContentText(getString(R.string.wechat_keep_alive_service_text))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xfeng_wechat_keep_alive"
        private const val NOTIFICATION_ID = 520134
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        const val EXTRA_WATCHDOG = "com.xiao.feng.extra.KEEP_ALIVE_WATCHDOG"
        const val EXTRA_NETWORK_HEARTBEAT = "com.xiao.feng.extra.KEEP_ALIVE_NETWORK_HEARTBEAT"

        fun start(context: Context, watchdog: Boolean, networkHeartbeat: Boolean) {
            val intent = Intent(context, WeChatKeepAliveService::class.java).apply {
                putExtra(EXTRA_WATCHDOG, watchdog)
                putExtra(EXTRA_NETWORK_HEARTBEAT, networkHeartbeat)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context): Boolean =
            context.stopService(Intent(context, WeChatKeepAliveService::class.java))
    }
}
