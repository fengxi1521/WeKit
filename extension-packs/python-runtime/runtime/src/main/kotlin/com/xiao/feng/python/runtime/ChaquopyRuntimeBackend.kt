package com.xiao.feng.python.runtime

import com.chaquo.python.Python
import com.xiao.feng.python.api.PythonPluginHost
import com.xiao.feng.python.api.PythonPluginRequest
import com.xiao.feng.python.api.PythonRuntimeBackend
import com.xiao.feng.python.api.PythonRuntimeConfig

internal class ChaquopyRuntimeBackend : PythonRuntimeBackend {
    private lateinit var config: PythonRuntimeConfig

    @Synchronized
    override fun start(config: PythonRuntimeConfig) {
        if (this::config.isInitialized) return
        this.config = config
        check(!Python.isStarted()) { "Chaquopy Python was started outside XiaoFeng's runtime backend" }
        Python.start(XiaoFengAndroidPlatform(config))
        withLookupLoader {
            Python.getInstance().getModule("java.chaquopy")
                .callAttrThrows("set_java_class_loader", config.lookupClassLoader)
            Python.getInstance().getModule("xfeng._bootstrap")
                .callAttrThrows("initialize", config)
        }
    }

    override fun activatePlugin(request: PythonPluginRequest, host: PythonPluginHost) = withLookupLoader {
        Python.getInstance().getModule("xfeng._bootstrap")
            .callAttrThrows("activate_plugin", request, host)
        Unit
    }

    override fun deactivatePlugin(pluginId: String) = withLookupLoader {
        Python.getInstance().getModule("xfeng._bootstrap").callAttrThrows("deactivate_plugin", pluginId)
        Unit
    }

    override fun reloadPlugin(request: PythonPluginRequest, host: PythonPluginHost) = withLookupLoader {
        Python.getInstance().getModule("xfeng._bootstrap")
            .callAttrThrows("reload_plugin", request, host)
        Unit
    }

    private inline fun <T> withLookupLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = config.lookupClassLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
