package com.xiao.feng.features.items.system

import android.content.Context
import androidx.annotation.StringRes
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalizedContextFactory
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo

fun localizedSystemString(@StringRes id: Int, vararg args: Any): String =
    HostInfo.application.systemLocalizedContext().getString(id, *args)

fun Context.localizedSystemString(@StringRes id: Int, vararg args: Any): String =
    systemLocalizedContext().getString(id, *args)

private fun Context.systemLocalizedContext(): Context = LocalizedContextFactory.create(
    this,
    XiaoFengLocaleController.resolvedLocale,
    LocaleResourceMode.InjectedHost,
)
