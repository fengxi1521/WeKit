package com.xiao.feng.features.items.shortvideos

import android.content.Context
import androidx.annotation.StringRes
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalizedContextFactory
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo

fun localizedShortVideoString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.shortVideoLocalizedContext().getString(id, *formatArgs)

private fun Context.shortVideoLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        XiaoFengLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
