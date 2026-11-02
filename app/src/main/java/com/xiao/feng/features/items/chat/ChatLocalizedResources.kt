package com.xiao.feng.features.items.chat

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalizedContextFactory
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.utils.HostInfo

fun localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    HostInfo.application.localizedChatString(id, *formatArgs)

fun Context.localizedChatString(@StringRes id: Int, vararg formatArgs: Any): String =
    chatLocalizedContext().getString(id, *formatArgs)

/**
 * Raw string template without format-argument substitution.
 *
 * [Context.getString] with no arguments still runs the formatter, so templates that contain
 * `%1$s`-style specifiers throw when they are resolved before their arguments are known.
 */
fun localizedChatText(@StringRes id: Int): String = HostInfo.application.localizedChatText(id)

fun Context.localizedChatText(@StringRes id: Int): String =
    chatLocalizedContext().resources.getText(id).toString()

fun localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = HostInfo.application.localizedChatQuantity(id, quantity, *formatArgs)

fun Context.localizedChatQuantity(
    @PluralsRes id: Int,
    quantity: Int,
    vararg formatArgs: Any,
): String = chatLocalizedContext().resources.getQuantityString(id, quantity, *formatArgs)

private fun Context.chatLocalizedContext(): Context =
    LocalizedContextFactory.create(
        this,
        XiaoFengLocaleController.resolvedLocale,
        LocaleResourceMode.InjectedHost,
    )
