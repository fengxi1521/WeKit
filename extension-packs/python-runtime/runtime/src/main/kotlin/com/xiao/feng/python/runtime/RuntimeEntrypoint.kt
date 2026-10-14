package com.xiao.feng.python.runtime

import com.xiao.feng.python.api.PythonPluginHost
import com.xiao.feng.python.api.PythonRuntimeApi
import com.xiao.feng.python.api.PythonRuntimeBackend
import com.xiao.feng.python.api.PythonRuntimeConfig
import com.xiao.feng.python.api.PythonRuntimeStartupException

/** Loader-neutral bootstrap. Chaquopy classes are resolved only after native loading completes. */
object RuntimeEntrypoint {
    @JvmStatic
    fun bootstrap(apiVersion: Int, config: PythonRuntimeConfig, host: PythonPluginHost): PythonRuntimeBackend {
        if (apiVersion != PythonRuntimeApi.API_VERSION) {
            throw PythonRuntimeStartupException(
                "API",
                message = "Unsupported Python runtime API version: $apiVersion",
            )
        }
        try {
            RuntimeNativeLoader.load(config)
        } catch (error: PythonRuntimeStartupException) {
            throw error
        } catch (error: Throwable) {
            throw PythonRuntimeStartupException(
                phase = "NATIVE",
                message = "Python native runtime bootstrap failed",
                cause = error,
            )
        }
        return try {
            ChaquopyRuntimeBackend()
        } catch (error: Throwable) {
            throw PythonRuntimeStartupException(
                phase = "BACKEND",
                message = "Python runtime backend could not be created",
                cause = error,
            )
        }
    }
}
