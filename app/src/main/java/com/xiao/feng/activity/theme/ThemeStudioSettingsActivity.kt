package com.xiao.feng.activity.theme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.XiaoFengLocaleProvider
import com.xiao.feng.ui.animation.predictiveback.xiaoFengNavTransition
import com.xiao.feng.ui.navigation.LocalNavigator
import com.xiao.feng.ui.navigation.Navigator
import com.xiao.feng.ui.navigation.rememberM3NavEffects
import com.xiao.feng.ui.utils.theme.ModuleTheme
import com.xiao.feng.ui.utils.theme.ThemeSettings
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * In-app theme studio: create, edit, select and delete theme folders without leaving XiaoFeng.
 *
 * Named `*SettingsActivity` on purpose so [com.xiao.feng.loader.utils.ActivityProxy] launches it
 * through the opaque settings proxy when opened from WeChat's host process.
 */
@Keep
class ThemeStudioSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiaoFengLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme(darkTheme = ThemeSettings.themeMode.resolve()) {
                    ThemeStudioRoot(onFinish = { finish() })
                }
            }
        }
    }
}

@Serializable
sealed interface ThemeStudioRoute : NavKey {
    @Serializable
    data object Home : ThemeStudioRoute

    @Serializable
    data class Editor(val themeId: String) : ThemeStudioRoute
}

@Composable
private fun ThemeStudioRoot(onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<ThemeStudioRoute>(ThemeStudioRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    // Bumped when an editor changes a manifest name or deletes a theme so Home re-scans folders.
    var revision by remember { mutableIntStateOf(0) }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                revision++
                if (navigator.backStackSize() <= 1) onFinish() else navigator.pop()
            },
            transition = xiaoFengNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<ThemeStudioRoute.Home> {
                ThemeStudioHomeScreen(
                    revision = revision,
                    onBack = onFinish,
                    onOpenTheme = { navigator.push(ThemeStudioRoute.Editor(it)) },
                )
            }
            entry<ThemeStudioRoute.Editor>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                ThemeEditorScreen(
                    themeId = key.themeId,
                    onBack = {
                        revision++
                        navigator.pop()
                    },
                )
            }
        }
    }
}
