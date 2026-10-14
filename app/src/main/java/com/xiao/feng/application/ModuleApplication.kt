package com.xiao.feng.application

import android.app.Application
import android.content.Context
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo

class ModuleApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        LspBootstrap.loadInstalled()
    }

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        XiaoFengLocaleController.initializeModuleProcess(this)
    }
}
