
package com.xiao.feng.activity.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Add_circle
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Call
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Checklist
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Code
import com.composables.icons.materialsymbols.outlined.Comedy_mask
import com.composables.icons.materialsymbols.outlined.Contact_page
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Imagesearch_roller
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Newspaper
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Terminal
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.composables.icons.materialsymbols.outlinedfilled.Article
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Tune
import com.xiao.feng.R
import com.xiao.feng.features.core.BaseFeature
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.LocaleResourceMode
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.i18n.XiaoFengLocaleProvider
import com.xiao.feng.data.KvStore
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.XfMasthead
import com.xiao.feng.ui.content.m3.XfTab
import com.xiao.feng.ui.content.m3.XfTabBar
import com.xiao.feng.ui.content.m3.xfPageBackground
import com.xiao.feng.ui.navigation.LocalNavigator
import com.xiao.feng.ui.navigation.Navigator
import com.xiao.feng.ui.navigation.rememberM3NavEffects
import com.xiao.feng.ui.utils.theme.ModuleTheme
import com.xiao.feng.ui.utils.theme.ThemeSettings
import com.xiao.feng.ui.animation.predictiveback.xiaoFengNavTransition
import com.xiao.feng.utils.WeLogger
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

val LocalComponentActivity = staticCompositionLocalOf<ComponentActivity> { error("not provided") }

@Keep
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CompositionLocalProvider(
                LocalComponentActivity provides this
            ) {
                XiaoFengLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                    ModuleTheme {
                        var licensed by remember { mutableStateOf(ModuleLicense.isVerified) }
                        if (licensed) {
                            SettingsRoot(onFinish = { finish() })
                        } else {
                            LicenseGate(onVerified = { licensed = true })
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Feature categories
// ---------------------------------------------------------------------------

data class FeatureCategory(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
)

val FEATURE_CATEGORIES = listOf(
    FeatureCategory(FeatureCategoryIds.CHAT, R.string.feature_category_chat_title, MaterialSymbols.Outlined.Chat),
    FeatureCategory(FeatureCategoryIds.CONTACTS_GROUPS, R.string.feature_category_contacts_groups_title, MaterialSymbols.Outlined.Contacts),
    FeatureCategory(FeatureCategoryIds.PAYMENT, R.string.feature_category_payment_title, MaterialSymbols.Outlined.Payments),
    FeatureCategory(FeatureCategoryIds.MOMENTS, R.string.feature_category_moments_title, MaterialSymbols.Outlined.Camera),
    FeatureCategory(FeatureCategoryIds.SYSTEM_PRIVACY, R.string.feature_category_system_privacy_title, MaterialSymbols.Outlined.Wand_stars),
    FeatureCategory(FeatureCategoryIds.VOIP, R.string.feature_category_voip_title, MaterialSymbols.Outlined.Call),
    FeatureCategory(FeatureCategoryIds.NOTIFICATIONS, R.string.feature_category_notifications_title, MaterialSymbols.Outlined.Notifications),
    FeatureCategory(FeatureCategoryIds.BEAUTIFY, R.string.feature_category_beautify_title, MaterialSymbols.Outlined.Imagesearch_roller),
    FeatureCategory(FeatureCategoryIds.OFFICIAL_ACCOUNTS, R.string.feature_category_official_accounts_title, MaterialSymbols.Outlined.Newspaper),
    FeatureCategory(FeatureCategoryIds.MINIAPPS, R.string.feature_category_miniapps_title, MaterialSymbols.Outlined.Package_2),
    FeatureCategory(FeatureCategoryIds.CHANNELS, R.string.feature_category_channels_title, MaterialSymbols.Outlined.Movie),
    FeatureCategory(FeatureCategoryIds.PROFILE, R.string.feature_category_profile_title, MaterialSymbols.Outlined.Account_circle),
    FeatureCategory(FeatureCategoryIds.DEBUG, R.string.feature_category_debug_title, MaterialSymbols.Outlined.Bug_report),
    FeatureCategory(FeatureCategoryIds.SCRIPTING_JAVA, R.string.feature_category_scripting_java_title, MaterialSymbols.Outlined.Terminal),
    FeatureCategory(FeatureCategoryIds.SCRIPTING_PYTHON, R.string.feature_category_scripting_python_title, MaterialSymbols.Outlined.Code),
    FeatureCategory(FeatureCategoryIds.ENTERTAIN, R.string.feature_category_entertain_title, MaterialSymbols.Outlined.Comedy_mask),
    FeatureCategory(FeatureCategoryIds.BATCH, R.string.feature_category_batch_title, MaterialSymbols.Outlined.Checklist),
    FeatureCategory(FeatureCategoryIds.HOME_SCREEN_MENU, R.string.feature_category_home_screen_menu_title, MaterialSymbols.Outlined.Add_circle),
    FeatureCategory(FeatureCategoryIds.CONTACT_DETAILS, R.string.feature_category_contact_details_title, MaterialSymbols.Outlined.Contact_page),
)

// Category icons are looked up for every feature row; build the map once instead of scanning
// FEATURE_CATEGORIES per composition.
val FEATURE_ICON_BY_CATEGORY = FEATURE_CATEGORIES.associate { it.id to it.icon }

/**
 * Pseudo-category shown above the real ones. Deliberately kept out of [FEATURE_CATEGORIES] —
 * it isn't something a feature can declare in its `categoryIds` property.
 */
const val NEW_FEATURES_CATEGORY = "new_features"
const val ENABLED_FEATURES_CATEGORY = "enabled_features"

@StringRes
fun featureCategoryTitleRes(categoryId: String): Int =
    when (categoryId) {
        NEW_FEATURES_CATEGORY -> R.string.feature_category_new_features_title
        ENABLED_FEATURES_CATEGORY -> R.string.feature_category_enabled_title
        FeatureCategoryIds.API -> R.string.feature_category_api_title
        else -> FEATURE_CATEGORIES.first { it.id == categoryId }.titleRes
    }

/**
 * Features whose source file entered the repo within [NewFeatures.WINDOW_DAYS] days of the build's
 * HEAD commit (joined by generated Feature source keys at compile time), newest first.
 *
 * Features that belong to no real category — the `API` internals — are dropped: they carry no
 * switch a user would meaningfully flip.
 */
val NEW_FEATURE_ITEMS: List<BaseFeature>
    get() = FeatureCategoryState.newItems

// ---------------------------------------------------------------------------
//  Root: three-tab pager + floating bottom bar, with category drill-down
// ---------------------------------------------------------------------------

/** Navigation targets for the Settings activity's stack. */
@Serializable
sealed interface SettingsRoute : NavKey {
    @Serializable
    data object Main : SettingsRoute
    @Serializable
    data class Category(val id: String) : SettingsRoute
    @Serializable
    data object License : SettingsRoute
}

@Composable
private fun SettingsRoot(onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<SettingsRoute>(SettingsRoute.Main)
    val navigator = remember(backStack) { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { TAB_ITEMS.size })
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (navigator.backStackSize() <= 1) onFinish() else navigator.pop()
            },
            transition = xiaoFengNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<SettingsRoute.Main> {
                MainPagerScreen(
                    pagerState = pagerState,
                    onOpenCategory = { navigator.push(SettingsRoute.Category(it)) },
                    onOpenLicense = { navigator.push(SettingsRoute.License) },
                )
            }
            entry<SettingsRoute.Category>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                CategoryDetailScreen(categoryId = key.id, onBack = { navigator.pop() })
            }
            entry<SettingsRoute.License>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                LicenseScreen(onBack = { navigator.pop() })
            }
        }
    }

    // Back at the stack root returns the pager to the home tab; further back finishes.
    BackHandler(enabled = navigator.backStackSize() == 1 && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
}

@Composable
private fun MainPagerScreen(
    pagerState: PagerState,
    onOpenCategory: (String) -> Unit,
    onOpenLicense: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val tabs = TAB_ITEMS.map {
        XfTab(
            label = stringResource(it.labelRes),
            icon = it.outlined,
            selectedIcon = it.filled,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { it },
        ) { page ->
            when (page) {
                0 -> HomePager(
                    onOpenFeatures = { scope.launch { pagerState.animateScrollToPage(1) } },
                )
                1 -> FeaturesPager(onOpenCategory = onOpenCategory)
                2 -> LogsPager()
                else -> SettingsPager(onOpenLicense = onOpenLicense)
            }
        }

        XfTabBar(
            tabs = tabs,
            selectedIndex = pagerState.targetPage,
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private data class NavItem(
    @StringRes val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val TAB_ITEMS = listOf(
    NavItem(R.string.nav_home, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home),
    NavItem(R.string.nav_features, MaterialSymbols.Outlined.Tune, MaterialSymbols.OutlinedFilled.Tune),
    NavItem(R.string.nav_logs, MaterialSymbols.Outlined.Article, MaterialSymbols.OutlinedFilled.Article),
    NavItem(R.string.nav_settings, MaterialSymbols.Outlined.Settings, MaterialSymbols.OutlinedFilled.Settings),
)

/** Bottom padding so scrollable content clears the tab bar. */
val CONTENT_BOTTOM_INSET = 112.dp

// ---------------------------------------------------------------------------
//  Shared scaffold (Material 3 Expressive)
// ---------------------------------------------------------------------------

@Composable
fun M3ListScaffold(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
    subtitle: String? = null,
    eyebrow: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .xfPageBackground(),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            item(key = "masthead") {
                XfMasthead(
                    title = title,
                    subtitle = subtitle,
                    eyebrow = eyebrow,
                    navigation = navigationIcon,
                )
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------
//  Shared feature row (Material 3) — used by category detail and search
// ---------------------------------------------------------------------------

@Composable
fun FeatureRow(
    item: BaseFeature,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalComponentActivity.current
    val localizedContext = LocalXiaoFengLocalizedContext.current
    val configKey = item.technicalId
    val localizedName = item.localizedName(localizedContext)
    val localizedDescription = item.localizedDescription(localizedContext)
    val icon = item.categoryIds.firstNotNullOfOrNull { FEATURE_ICON_BY_CATEGORY[it] }

    fun toggle(requested: Boolean) {
        item as SwitchFeature
        if (item.onBeforeToggle(requested, context)) {
            KvStore.putBool(configKey, requested)
            item.isEnabled = requested
            FeatureCategoryState.notifyToggleChanged()
            onCheckedChange(requested)
        }
    }

    when (item) {
        is ClickableFeature -> {
            val openConfig: () -> Unit = {
                runCatching { item.onClick(context) }
                    .onFailure { WeLogger.e("SettingsActivity", "onClick failed for ${item.technicalPath}", it) }
            }

            if (item.noSwitchWidget) {
                // Pure action row: the whole row triggers the feature's action.
                BaseWidget(
                    icon = icon,
                    title = localizedName,
                    description = localizedDescription,
                    onClick = openConfig,
                    trailingContent = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Chevron_right,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            } else {
                // Dual click areas: the main area opens the feature's config, the
                // switch past the divider toggles it (WeAgent "Memory" row pattern).
                SwitchWidget(
                    icon = icon,
                    title = localizedName,
                    description = localizedDescription,
                    onClick = openConfig,
                    trailingDivider = true,
                    checked = checked,
                    onCheckedChange = { toggle(it) },
                )
            }
        }

        is SwitchFeature -> SwitchWidget(
            icon = icon,
            title = localizedName,
            description = localizedDescription,
            checked = checked,
            onCheckedChange = { toggle(it) },
        )
    }
}
