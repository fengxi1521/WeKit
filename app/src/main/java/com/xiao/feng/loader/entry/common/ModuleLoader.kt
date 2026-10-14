package com.xiao.feng.loader.entry.common

import android.util.Log
import com.xiao.feng.loader.abc.IHookBridge
import com.xiao.feng.loader.abc.ILoaderService
import com.xiao.feng.loader.startup.UnifiedEntryPoint
import com.xiao.feng.loader.utils.NativeLoader
import com.xiao.feng.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private val initLock = Any()

    @Volatile
    private var isInitialized = false

    @Suppress("unused")
    @JvmStatic
    fun init(
        hostDataDir: String,
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        allowDynamicLoad: Boolean
    ): Boolean = synchronized(initLock) {
        if (isInitialized) return@synchronized true

        try {
            NativeLoader.initDecoder(modulePath)
        } catch (t: Throwable) {
            // The normal logger may itself contain protected strings.
            Log.e("XiaoFeng", "string decoder bootstrap failed", t)
            return@synchronized false
        }

        try {
            WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
            UnifiedEntryPoint.entry(loaderService, hookBridge, initialClassLoader, modulePath)
            isInitialized = true
            true
        } catch (t: Throwable) {
            // Do not poison this process's loader state: a later lifecycle
            // callback may have a usable host class loader.
            WeLogger.e(TAG, "UnifiedEntryPoint failed", t)
            false
        }
    }
}
