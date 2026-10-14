package com.xiao.feng.loader.startup

import android.content.Context
import com.xiao.feng.constants.PackageNames
import com.xiao.feng.dexkit.cache.DexCacheManager
import com.xiao.feng.features.core.FeaturesLoader
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.loader.utils.ActivityProxy
import com.xiao.feng.loader.utils.ParcelableFixer
import com.xiao.feng.loader.utils.ResourcesInjector
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.RuntimeConfig
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.fs.KnownPaths

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            KnownPaths.hostVersionKey()
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        XiaoFengLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
