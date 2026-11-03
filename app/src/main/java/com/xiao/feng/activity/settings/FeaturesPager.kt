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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.xiao.feng.R
import com.xiao.feng.features.core.BaseFeature
import com.xiao.feng.features.core.FeaturesProvider
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.core.featureCategoryComparator
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.i18n.XiaoFengLocaleController
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
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
    val context = LocalXiaoFengLocalizedContext.current
    val resolvedLocale = XiaoFengLocaleController.resolvedLocale
    val revision = FeatureCategoryState.revision
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
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
                SectionLabel(
                    text = stringResource(R.string.home_stat_categories),
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 14.dp),
                )
            }
            items(
                count = (FEATURE_CATEGORIES.size + 1) / 2,
                key = { index -> "category_row_$index" },
            ) { index ->
                val rowItems = FEATURE_CATEGORIES.subList(
                    index * 2,
                    minOf(index * 2 + 2, FEATURE_CATEGORIES.size),
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

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

/** Outlined shortcut chip with a count bubble; replaces the two identical spotlight tiles. */
@Composable
private fun QuickChip(
    label: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerLowest.copy(alpha = 0.66f))
            .border(1.dp, scheme.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(18.dp),
        )
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
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = scheme.onPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.primary)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** Two-column category cell: framed icon, name, feature count and a small chevron. */
@Composable
private fun CategoryTile(
    category: FeatureCategory,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .height(124.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerLowest.copy(alpha = 0.7f))
            .border(1.dp, scheme.outline.copy(alpha = 0.2f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(scheme.primary.copy(alpha = 0.09f))
                    .border(1.dp, scheme.primary.copy(alpha = 0.24f), RoundedCornerShape(9.dp)),
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
            Icon(
                imageVector = MaterialSymbols.Outlined.Chevron_right,
                contentDescription = null,
                tint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
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
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.features_category_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
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
