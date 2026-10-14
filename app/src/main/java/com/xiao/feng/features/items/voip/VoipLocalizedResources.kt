package com.xiao.feng.features.items.voip

import androidx.annotation.StringRes
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalizedContextFactory
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo

fun localizedVoipString(@StringRes id: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        HostInfo.application,
        XiaoFengLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    ).getString(id, *formatArgs)
