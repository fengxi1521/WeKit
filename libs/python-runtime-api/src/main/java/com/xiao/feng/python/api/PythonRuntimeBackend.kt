package com.xiao.feng.python.api

interface PythonRuntimeBackend {
    fun start(config: PythonRuntimeConfig)

    fun activatePlugin(request: PythonPluginRequest, host: PythonPluginHost)

    fun deactivatePlugin(pluginId: String)

    fun reloadPlugin(request: PythonPluginRequest, host: PythonPluginHost)
}
