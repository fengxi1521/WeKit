package com.xiao.feng.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Sort
import com.composables.icons.materialsymbols.outlined.Star
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.xiao.feng.R
import com.xiao.feng.features.core.BaseFeature
import com.xiao.feng.features.core.FeaturesProvider
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.core.featureCategoryComparator
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.i18n.XiaoFengLocaleController
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.ExpressiveBackButton
import com.xiao.feng.ui.content.m3.SectionLabel
import com.xiao.feng.ui.content.m3.XfSearchBar
import com.xiao.feng.ui.content.m3.lazySegmentedItems
import java.text.Collator
import java.util.Locale


/**
 * Current switch state of [item], served from [FeatureCategoryState]'s per-revision snapshot so the
 * whole list costs one batched query instead of one SQLite query per row.
 */
@Composable
private fun featureChecked(item: BaseFeature): Boolean = FeatureCategoryState.isEnabled(item)

// ---------------------------------------------------------------------------
//  Page 1 — Features (command search + category grid)
// ---------------------------------------------------------------------------

@Composable
fun FeaturesPager(
    onOpenCategory: (String) -> Unit,
    onOpenMore: () -> Unit,
    onOpenFavorites: () -> Unit,
) {
    val context = LocalXiaoFengLocalizedContext.current
    val resolvedLocale = XiaoFengLocaleController.resolvedLocale
    val revision = FeatureCategoryState.revision
    val favoritesRevision = FeatureFavorites.revision
    val favoritesCount = remember(favoritesRevision) { FeatureFavorites.ids().size }
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()

    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val searchableItems = remember(resolvedLocale) {
        FeaturesProvider.ALL_FEATURES
            .filterIsInstance<SwitchFeature>()
            .sortedWith { first, second ->
                featureNameCollator.compare(first.localizedName(context), second.localizedName(context))
            }
    }
    val filteredItems = remember(query, resolvedLocale, revision) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.localizedName(context).contains(query, ignoreCase = true) ||
                it.localizedDescription(context).contains(query, ignoreCase = true)
        }
    }
    val categoryCounts = remember {
        FEATURE_CATEGORIES.associate { category ->
            category.id to FeaturesProvider.ALL_FEATURES.count { category.id in it.categoryIds }
        }
    }

    // A back press while searching clears the query first (after the IME's own
    // back has dismissed the keyboard) rather than exiting the module settings.
    BackHandler(enabled = searching) { query = "" }

    M3ListScaffold(title = stringResource(R.string.nav_features)) {
        item {
            XfSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.features_search_hint),
                clearContentDescription = stringResource(R.string.features_clear_search),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (searching) {
            // Search results replace the category grid while a query is active.
            if (filteredItems.isEmpty()) {
                item { SearchEmptyState() }
            } else {
                item { Spacer(Modifier.height(8.dp)) }
                lazySegmentedItems(filteredItems, key = { it.technicalId }) { feature ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        FeatureRow(
                            item = feature,
                            checked = featureChecked(feature),
                            onCheckedChange = {},
                        )
                    }
                }
            }
        } else {
            item { Spacer(Modifier.height(14.dp)) }
            // 收藏入口固定在功能页最顶部, 用户可把常用功能收进来并自由排序。
            item {
                FeaturesNavEntry(
                    icon = MaterialSymbols.Outlined.Star,
                    title = stringResource(R.string.features_favorites_title),
                    count = favoritesCount,
                    onClick = onOpenFavorites,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickChip(
                        label = stringResource(featureCategoryTitleRes(NEW_FEATURES_CATEGORY)),
                        count = FeatureCategoryState.newItems.size,
                        icon = MaterialSymbols.Outlined.Wand_stars,
                        onClick = { onOpenCategory(NEW_FEATURES_CATEGORY) },
                        modifier = Modifier.weight(1f),
                    )
                    QuickChip(
                        label = stringResource(featureCategoryTitleRes(ENABLED_FEATURES_CATEGORY)),
                        count = FeatureCategoryState.enabledItems().size,
                        icon = MaterialSymbols.Outlined.Check_circle,
                        onClick = { onOpenCategory(ENABLED_FEATURES_CATEGORY) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                FeaturesNavEntry(
                    icon = MaterialSymbols.Outlined.Expand_more,
                    title = stringResource(R.string.features_more_title),
                    count = MORE_FEATURE_CATEGORIES.sumOf { categoryCounts[it.id] ?: 0 },
                    onClick = onOpenMore,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp),
                )
            }
            item {
                SectionLabel(
                    text = stringResource(R.string.home_stat_categories),
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 14.dp),
                )
            }
            featuresCategoryGrid(
                categories = MAIN_FEATURE_CATEGORIES,
                categoryCounts = categoryCounts,
                onOpenCategory = onOpenCategory,
            )
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

/** Reusable two-column category grid, shared by the feature home page and the 更多功能 page. */
fun LazyListScope.featuresCategoryGrid(
    categories: List<FeatureCategory>,
    categoryCounts: Map<String, Int>,
    onOpenCategory: (String) -> Unit,
) {
    items(
        count = (categories.size + 1) / 2,
        key = { index -> "category_row_$index" },
    ) { index ->
        val rowItems = categories.subList(
            index * 2,
            minOf(index * 2 + 2, categories.size),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rowItems.forEach { category ->
                CategoryTile(
                    category = category,
                    count = categoryCounts[category.id] ?: 0,
                    onClick = { onOpenCategory(category.id) },
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowItems.size == 1) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Full-width entry row that opens a secondary feature page (收藏 / 更多功能). */
@Composable
private fun FeaturesNavEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.68f))
            .border(1.dp, scheme.primary.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(50))
                .background(scheme.primary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(scheme.primary.copy(alpha = 0.10f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = MaterialSymbols.Outlined.Chevron_right,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Frosted capsule shortcut with a gradient count bubble. */
@Composable
private fun QuickChip(
    label: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.68f))
            .border(1.dp, scheme.primary.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(50))
                .background(scheme.primary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(scheme.primary, scheme.tertiary),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimary,
            )
        }
    }
}

/** Two-column glass category cell: round icon badge, name, count capsule. */
@Composable
private fun CategoryTile(
    category: FeatureCategory,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .height(128.dp)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = scheme.primary.copy(alpha = 0.14f),
                spotColor = scheme.primary.copy(alpha = 0.20f),
            )
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.68f))
            .border(1.dp, scheme.primary.copy(alpha = 0.13f), shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(category.titleRes),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun SearchEmptyState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 32.dp, end = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.features_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

@Composable
fun CategoryDetailScreen(categoryId: String, onBack: () -> Unit) {
    val context = LocalXiaoFengLocalizedContext.current
    val resolvedLocale = XiaoFengLocaleController.resolvedLocale
    val revision = FeatureCategoryState.revision
    val featureNameCollator = remember(resolvedLocale) {
        Collator.getInstance(Locale.forLanguageTag(resolvedLocale.androidTag))
    }
    val items = remember(categoryId, resolvedLocale, revision) {
        when (categoryId) {
            NEW_FEATURES_CATEGORY -> FeatureCategoryState.newItems
            ENABLED_FEATURES_CATEGORY -> FeatureCategoryState.enabledItems()
            else -> FeaturesProvider.ALL_FEATURES
                .filter { categoryId in it.categoryIds }
                .sortedWith(
                    featureCategoryComparator { first, second ->
                        featureNameCollator.compare(
                            first.localizedName(context),
                            second.localizedName(context),
                        )
                    },
                )
        }
    }

    M3ListScaffold(
        title = stringResource(featureCategoryTitleRes(categoryId)),
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
        subtitle = stringResource(R.string.features_category_count, items.size),
    ) {
        if (items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.features_category_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            lazySegmentedItems(items, key = { it.technicalId }) { feature ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FeatureRow(
                        item = feature,
                        checked = featureChecked(feature),
                        onCheckedChange = {},
                    )
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Favorites — user-curated list of frequently used features
// ---------------------------------------------------------------------------

@Composable
fun FavoritesScreen(onBack: () -> Unit) {
    val context = LocalXiaoFengLocalizedContext.current
    val revision = FeatureCategoryState.revision
    val favoritesRevision = FeatureFavorites.revision
    var adding by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val favorites = remember(revision, favoritesRevision) { FeatureFavorites.features() }
    val allSwitchFeatures = remember { FeaturesProvider.ALL_FEATURES.filterIsInstance<SwitchFeature>() }
    val candidates = remember(adding, query, favoritesRevision) {
        if (!adding) {
            emptyList()
        } else {
            allSwitchFeatures
                .filter { !FeatureFavorites.contains(it.technicalId) }
                .filter {
                    query.isBlank() ||
                        it.localizedName(context).contains(query, ignoreCase = true) ||
                        it.localizedDescription(context).contains(query, ignoreCase = true)
                }
                .sortedBy { it.localizedName(context) }
        }
    }

    M3ListScaffold(
        title = stringResource(R.string.features_favorites_title),
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
        subtitle = stringResource(R.string.features_category_count, favorites.size),
    ) {
        item { Spacer(Modifier.height(14.dp)) }

        if (adding) {
            item {
                XfSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.features_search_hint),
                    clearContentDescription = stringResource(R.string.features_clear_search),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (candidates.isEmpty()) {
                item {
                    FavoritesEmptyState(
                        stringResource(
                            if (query.isBlank()) R.string.features_favorites_all_added
                            else R.string.features_no_results,
                        ),
                    )
                }
            } else {
                lazySegmentedItems(candidates, key = { it.technicalId }) { feature ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        val icon = feature.categoryIds.firstNotNullOfOrNull { FEATURE_ICON_BY_CATEGORY[it] }
                        BaseWidget(
                            icon = icon,
                            title = feature.localizedName(context),
                            description = feature.localizedDescription(context).ifBlank { null },
                            onClick = { FeatureFavorites.add(feature) },
                            trailingContent = {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FavoritesActionChip(
                        icon = MaterialSymbols.Outlined.Check_circle,
                        label = stringResource(R.string.features_favorites_done),
                        onClick = {
                            adding = false
                            query = ""
                        },
                        modifier = Modifier.weight(1f),
                        active = true,
                    )
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FavoritesActionChip(
                        icon = MaterialSymbols.Outlined.Add,
                        label = stringResource(R.string.features_favorites_add),
                        onClick = {
                            adding = true
                            editMode = false
                            query = ""
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (favorites.isNotEmpty()) {
                        FavoritesActionChip(
                            icon = MaterialSymbols.Outlined.Sort,
                            label = stringResource(
                                if (editMode) R.string.features_favorites_done
                                else R.string.features_favorites_sort,
                            ),
                            onClick = { editMode = !editMode },
                            modifier = Modifier.weight(1f),
                            active = editMode,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }

            when {
                favorites.isEmpty() -> item {
                    FavoritesEmptyState(stringResource(R.string.features_favorites_empty))
                }

                editMode -> lazySegmentedItems(
                    favorites.withIndex().toList(),
                    key = { it.value.technicalId },
                ) { indexed ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        FavoriteEditRow(
                            feature = indexed.value,
                            index = indexed.index,
                            lastIndex = favorites.lastIndex,
                            onMoveUp = { FeatureFavorites.move(indexed.index, -1) },
                            onMoveDown = { FeatureFavorites.move(indexed.index, +1) },
                            onRemove = { FeatureFavorites.remove(indexed.value) },
                        )
                    }
                }

                else -> lazySegmentedItems(favorites, key = { it.technicalId }) { feature ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        FeatureRow(
                            item = feature,
                            checked = featureChecked(feature),
                            onCheckedChange = {},
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

/** Reorder/remove row shown in the favorites sort mode. */
@Composable
private fun FavoriteEditRow(
    feature: SwitchFeature,
    index: Int,
    lastIndex: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalXiaoFengLocalizedContext.current
    val icon = feature.categoryIds.firstNotNullOfOrNull { FEATURE_ICON_BY_CATEGORY[it] }
    BaseWidget(
        icon = icon,
        title = feature.localizedName(context),
        description = feature.localizedDescription(context).ifBlank { null },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Expand_more,
                        contentDescription = stringResource(R.string.features_favorites_move_up),
                        modifier = Modifier.size(20.dp).rotate(180f),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = index < lastIndex) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Expand_more,
                        contentDescription = stringResource(R.string.features_favorites_move_down),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Close,
                        contentDescription = stringResource(R.string.features_favorites_remove),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

/** Small pill action used on the favorites page (添加功能 / 排序 / 完成). */
@Composable
private fun FavoritesActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (active) scheme.primary.copy(alpha = 0.16f) else scheme.surface.copy(alpha = 0.68f),
            )
            .border(1.dp, scheme.primary.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FavoritesEmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, start = 32.dp, end = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun MoreFeaturesScreen(onBack: () -> Unit, onOpenCategory: (String) -> Unit) {
    val categoryCounts = remember {
        FEATURE_CATEGORIES.associate { category ->
            category.id to FeaturesProvider.ALL_FEATURES.count { category.id in it.categoryIds }
        }
    }
    val total = remember(categoryCounts) {
        MORE_FEATURE_CATEGORIES.sumOf { categoryCounts[it.id] ?: 0 }
    }
    M3ListScaffold(
        title = stringResource(R.string.features_more_title),
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
        subtitle = stringResource(R.string.features_category_count, total),
    ) {
        item { Spacer(Modifier.height(14.dp)) }
        featuresCategoryGrid(
            categories = MORE_FEATURE_CATEGORIES,
            categoryCounts = categoryCounts,
            onOpenCategory = onOpenCategory,
        )
        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
