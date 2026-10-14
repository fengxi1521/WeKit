package com.xiao.feng.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.XiaoFengLocaleProvider
import com.xiao.feng.utils.HostInfo

/**
 * Theme for XiaoFeng UI injected INTO WeChat.
 *
 * The seed is [SeedResolver.injectedSeed]: WeChat green by default, or the selected seed when
 * opted into WeChat ([ThemeSettings.applyToWechat]). This is read once when the composition
 * enters — it does NOT re-theme live (the user must restart WeChat for a change to apply).
 *
 * NEVER CALL THIS INSIDE MODULE APP.
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    XiaoFengLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
        val dark = darkTheme ?: isSystemInDarkTheme()
        val applyCustom = ThemeSettings.applyToWechat
        val seed = SeedResolver.injectedSeed(HostInfo.application, dark)

        val materialScheme = if (!applyCustom) {
            if (dark) darkScheme else lightScheme
        } else {
            SeedResolver.materialScheme(seed, dark)
        }

        MaterialExpressiveTheme(
            colorScheme = materialScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
