package com.xiao.feng.features.items.batch

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalizedContextFactory
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo

fun localizedBatchString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedBatchString(id, *formatArgs)

fun Context.localizedBatchString(@StringRes id: Int, vararg formatArgs: Any): String =
    batchLocalizedContext().getString(id, *formatArgs)

fun localizedBatchQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedBatchQuantity(id, quantity, *formatArgs)

fun Context.localizedBatchQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = batchLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.batchLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        XiaoFengLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
