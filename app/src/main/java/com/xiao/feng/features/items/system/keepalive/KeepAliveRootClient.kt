package com.xiao.feng.features.items.system.keepalive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.xiao.feng.constants.PackageNames
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Host-side client of [KeepAliveRootService]. Every call blocks, so it must stay on a worker
 * thread; [WeChatKeepAliveRuntime] already runs its root work on its own threads.
 */
object KeepAliveRootClient {

    private const val TAG = "KeepAliveRootClient"
    private const val TIMEOUT_SECONDS = 20L

    fun sync(
        dozeWhitelist: Boolean,
        appOps: Boolean,
        foregroundService: Boolean,
        watchdog: Boolean,
        networkHeartbeat: Boolean,
    ): RootStatus? {
        val latch = CountDownLatch(1)
        var status: RootStatus? = null
        val reply = Messenger(
            Handler(Looper.getMainLooper()) { message ->
                val data = message.data
                status = RootStatus(
                    data.getBoolean(KeepAliveRootService.KEY_ROOT),
                    data.getBoolean(KeepAliveRootService.KEY_DOZE),
                    data.getBoolean(KeepAliveRootService.KEY_APP_OPS),
                )
                latch.countDown()
                true
            },
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    latch.countDown()
                    return
                }
                val request = Message.obtain(null, KeepAliveRootService.MSG_SYNC).apply {
                    data = Bundle().apply {
                        putBoolean(KeepAliveRootService.KEY_DOZE, dozeWhitelist)
                        putBoolean(KeepAliveRootService.KEY_APP_OPS, appOps)
                        putBoolean(KeepAliveRootService.KEY_FOREGROUND_SERVICE, foregroundService)
                        putBoolean(KeepAliveRootService.KEY_WATCHDOG, watchdog)
                        putBoolean(KeepAliveRootService.KEY_HEARTBEAT, networkHeartbeat)
                    }
                    replyTo = reply
                }
                if (runCatching { Messenger(binder).send(request) }.isFailure) latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }

        val context = HostInfo.application
        val intent = Intent().setClassName(PackageNames.MODULE, KeepAliveRootService::class.java.name)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { WeLogger.w(TAG, "module app is not available for root commands", it) }
            .getOrDefault(false)
        if (!bound) return null

        val replied = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        runCatching { context.unbindService(connection) }
        if (!replied) {
            WeLogger.w(TAG, "module app did not answer the keep-alive request in time")
            return null
        }
        return status
    }

    /** Stops the foreground service owned by the module app. Blocks; call from a worker thread. */
    fun stop() {
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null) {
                    runCatching {
                        Messenger(binder).send(Message.obtain(null, KeepAliveRootService.MSG_STOP))
                    }
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {}

            override fun onNullBinding(name: ComponentName?) {
                latch.countDown()
            }
        }
        val context = HostInfo.application
        val intent = Intent().setClassName(PackageNames.MODULE, KeepAliveRootService::class.java.name)
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!bound) return
        latch.await(5, TimeUnit.SECONDS)
        runCatching { context.unbindService(connection) }
    }
}
