// SPDX-License-Identifier: GPL-3.0-only
package com.xiao.feng.ui.content.m3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Lazy segmented rows: consecutive items share flat seams and only the group's outer edges are
 * rounded. Radii are static per index, so scrolling a category list does not run a
 * `animateDpAsState` per visible row.
 */
fun <T> LazyListScope.lazySegmentedItems(
    items: List<T>,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit,
) {
    itemsIndexed(
        items = items,
        key = { _, item -> key(item) },
    ) { index, item ->
        val top: androidx.compose.ui.unit.Dp =
            if (index == 0) CornerRadius else ConnectionRadius
        val bottom: androidx.compose.ui.unit.Dp =
            if (index == items.lastIndex) CornerRadius else ConnectionRadius
        val shape = RoundedCornerShape(
            topStart = top,
            topEnd = top,
            bottomStart = bottom,
            bottomEnd = bottom,
        )

        Box(
            Modifier
                .animateItem(),
        ) {
            Column {
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                    )
                }
                CompositionLocalProvider(LocalSegmentedItemShape provides shape) {
                    itemContent(item)
                }
            }
        }
    }
}
