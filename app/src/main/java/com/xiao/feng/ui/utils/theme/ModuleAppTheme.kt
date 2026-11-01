package com.xiao.feng.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.XiaoFengLocaleProvider

/**
 * Theme for the module app itself (launcher entry, onboarding, error screens).
 *
 * Uses XiaoFeng's own brand palette rather than the WeChat-green scheme shared with
 * [InjectedUiTheme]: [SeedResolver.materialScheme] derived from [ThemeSettings.BRAND_SEED_COLOR].
 */
@Composable
fun ModuleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    XiaoFengLocaleProvider(mode = LocaleResourceMode.ModuleApp) {
        val colorScheme = SeedResolver.materialScheme(ThemeSettings.BRAND_SEED_COLOR, darkTheme)
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
