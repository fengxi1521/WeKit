package com.xiao.feng.loader.startup

import android.content.Context
import com.xiao.feng.constants.PackageNames
import com.xiao.feng.features.core.FeaturesLoader
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.loader.utils.ActivityProxy
import com.xiao.feng.loader.utils.ParcelableFixer
import com.xiao.feng.loader.utils.ResourcesInjector
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.RuntimeConfig
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        XiaoFengLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)
        }

        // :push 等后台进程里的功能 (如艾特回复、QQ 点歌) 需要读取自己的 wxid, 因此这里也初始化。
        RuntimeConfig.mmPrefs = context.getSharedPreferences(
            "${PackageNames.WECHAT}_preferences",
            Context.MODE_PRIVATE,
        )

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
