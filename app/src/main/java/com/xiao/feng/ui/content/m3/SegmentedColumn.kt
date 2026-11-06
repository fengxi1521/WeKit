// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors

package com.xiao.feng.ui.content.m3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PADDING_HORIZONTAL = 16
private const val PADDING_VERTICAL = 8

@DslMarker
annotation class SegmentedColumnDsl

/**
 * Represents the configuration and content of an individual item within a [SegmentedColumn].
 *
 * @property key A unique identifier for the item, utilized for optimized composition and state tracking.
 * @property visible Whether the item is currently part of the group. Hidden items are skipped
 * entirely instead of being measured and animated, which keeps long settings pages cheap to lay out.
 * @property customTopPadding Optional custom padding applied to the top of this specific item.
 * @property forceFlatTop If `true`, overrides the default corner rounding and forces the top corner radius to 0.dp.
 * @property forceFlatBottom If `true`, overrides the default corner rounding and forces the bottom corner radius to 0.dp.
 * @property content The composable payload of the item, which receives the calculated [Shape].
 */
@Immutable
data class SegmentedItemData(
    val key: Any?,
    val visible: Boolean,
    val customTopPadding: Dp? = null,
    val forceFlatTop: Boolean = false,
    val forceFlatBottom: Boolean = false,
    val content: @Composable (Shape) -> Unit
)

/**
 * A DSL scope used to define the items and their layout behaviors within a [SegmentedColumn].
 */
@SegmentedColumnDsl
class SegmentedColumnScope {
    val items = mutableListOf<SegmentedItemData>()

    /**
     * Registers a standard item within the group.
     *
     * @param key A unique identifier for the item. Defaults to the current index.
     * @param animatedVisibility Kept for source compatibility: hidden items are omitted rather than
     * animated, because per-item radius/padding springs were a measurable scroll cost.
     * @param topPadding Optional explicit top padding for this item.
     * @param forceFlatTop Disables top corner rounding if `true`.
     * @param forceFlatBottom Disables bottom corner rounding if `true`.
     * @param content The composable content representing the UI of this item.
     */
    fun item(
        key: Any? = null,
        animatedVisibility: Boolean = true,
        topPadding: Dp? = null,
        forceFlatTop: Boolean = false,
        forceFlatBottom: Boolean = false,
        content: @Composable (Shape) -> Unit
    ) {
        items.add(
            SegmentedItemData(
                key = key ?: items.size,
                visible = animatedVisibility,
                customTopPadding = topPadding,
                forceFlatTop = forceFlatTop,
                forceFlatBottom = forceFlatBottom,
                content = content,
            ),
        )
    }

    /**
     * Registers an expandable item pairing a header with conditionally visible body content.
     *
     * This method orchestrates the dynamic corner radius flattening between the header and the body
     * at the engine level. When expanded, the seam between the two components seamlessly loses its
     * internal rounded corners to appear as a single unified container.
     */
    fun expandableItem(
        animatedVisibility: Boolean = true,
        expanded: Boolean,
        topPadding: Dp? = null,
        bottomPadding: Dp = 0.dp,
        topContent: @Composable (Shape) -> Unit,
        bottomContent: @Composable (Shape) -> Unit,
    ) {
        item(
            animatedVisibility = animatedVisibility,
            topPadding = topPadding,
            forceFlatBottom = expanded,
            content = topContent,
        )
        item(
            animatedVisibility = animatedVisibility && expanded,
            topPadding = bottomPadding,
            forceFlatTop = true,
            content = bottomContent,
        )
    }
}

/**
 * A vertical group of visually spliced rows: the outer edges get [CornerRadius], interior edges get
 * [ConnectionRadius], so a group reads as one rounded card.
 *
 * Radii are computed once from the visible index instead of being animated per item: the previous
 * implementation created an [androidx.compose.animation.core.animateDpAsState] per row plus a custom
 * clip shape that re-measured on every frame. With groups of 10+ rows on the settings screen this
 * was one of the main scroll-time costs.
 */
@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    contentPadding: PaddingValues = PaddingValues(horizontal = PADDING_HORIZONTAL.dp, vertical = PADDING_VERTICAL.dp),
    titlePadding: PaddingValues = PaddingValues(start = 2.dp, top = PADDING_VERTICAL.dp, bottom = 12.dp),
    content: SegmentedColumnScope.() -> Unit,
) {
    val scope = SegmentedColumnScope().apply(content)
    val visibleItems = scope.items.filter { it.visible }

    if (visibleItems.isEmpty()) return

    Column(modifier = modifier.padding(contentPadding)) {
        if (title.isNotEmpty()) {
            // iOS grouped-list section header: plain secondary caption, no capsule or rule.
            Text(
                text = title,
                modifier = Modifier.padding(titlePadding),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        visibleItems.forEachIndexed { index, itemData ->
            key(itemData.key) {
                val topRadius = when {
                    itemData.forceFlatTop -> 0.dp
                    index == 0 -> CornerRadius
                    else -> ConnectionRadius
                }
                val bottomRadius = when {
                    itemData.forceFlatBottom -> 0.dp
                    index == visibleItems.lastIndex -> CornerRadius
                    else -> ConnectionRadius
                }
                val shape = ContinuousRoundedCornerShape(
                    topStart = topRadius,
                    topEnd = topRadius,
                    bottomStart = bottomRadius,
                    bottomEnd = bottomRadius,
                )

                CompositionLocalProvider(LocalSegmentedItemShape provides shape) {
                    Column(
                        modifier = Modifier.padding(
                            top = itemData.customTopPadding ?: 0.dp,
                        ),
                    ) {
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                thickness = 0.5.dp,
                                color = liquidGlassDividerColor(),
                            )
                        }
                        itemData.content(shape)
                    }
                }
            }
        }
    }
}
