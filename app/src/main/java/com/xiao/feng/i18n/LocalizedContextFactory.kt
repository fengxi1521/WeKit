package com.xiao.feng.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap
import dev.ujhhgtg.lsparanoid.runtime.LspResourceContext
import com.xiao.feng.loader.utils.ResourcesInjector
import java.util.concurrent.ConcurrentHashMap

enum class LocaleResourceMode {
    InjectedHost,
    ModuleApp,
}

object LocalizedContextFactory {

    /**
     * Every localized-string helper used to build a fresh configuration context (plus a module
     * resource injection) on each call, which is far too expensive for per-row / per-message
     * call sites. A localized context only depends on the locale and the injection mode, so it
     * is safe to build it once and reuse it.
     *
     * The context is derived from the application context so the cache never pins an Activity.
     */
    private val cache = ConcurrentHashMap<String, Context>()

    fun create(
        base: Context,
        locale: SupportedLocale,
        mode: LocaleResourceMode,
    ): Context {
        val key = "${mode.name}|${locale.name}"
        cache[key]?.let { return it }

        val application = base.applicationContext ?: base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale.androidTag))
        }
        val configured = application.createConfigurationContext(configuration)
        val localized = when (mode) {
            LocaleResourceMode.InjectedHost -> configured.also {
                ResourcesInjector.injectModuleRes(it.resources)
            }
            LocaleResourceMode.ModuleApp -> configured
        }
        val decoded = LspResourceContext(localized, LspBootstrap::decode, LspBootstrap.namespace)
        val context = if (locale == SupportedLocale.MEOW_CHINESE) {
            MeowResourcesContext(decoded)
        } else {
            decoded
        }
        return cache.getOrPut(key) { context }
    }
}
