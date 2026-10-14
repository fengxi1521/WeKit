package com.xiao.feng.loader.startup

import com.xiao.feng.loader.abc.IHookBridge
import com.xiao.feng.loader.abc.ILoaderService

object StartupInfo {

    lateinit var modulePath: String
    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
