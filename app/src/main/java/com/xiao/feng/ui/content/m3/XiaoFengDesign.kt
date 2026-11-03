package com.xiao.feng.ui.content.m3

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One tap target shown in [XiaoFengDock].
 */
data class XfDockItem(
    val label: String,
    val icon: ImageVector,
)

/**
 * Static page backdrop: a soft vertical wash plus two near-transparent accent glows.
 *
 * Everything is drawn from a cached brush inside [drawWithCache], so scrolling never allocates or
 * re-reads theme state per frame — this replaces the previous backdrop-blur layers that forced a
 * full-content blur render on every scroll frame.
 */
@Composable
fun Modifier.xfPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return drawWithCache {
        val wash = Brush.verticalGradient(
            0f to scheme.surfaceContainerLow,
            0.32f to scheme.surfaceContainer,
            1f to scheme.surfaceContainerLow,
        )
        val topGlow = scheme.primary.copy(alpha = 0.055f)
        val bodyGlow = scheme.tertiary.copy(alpha = 0.035f)
        onDrawBehind {
            drawRect(wash)
            drawCircle(
                color = topGlow,
                radius = size.width * 0.85f,
                center = Offset(size.width * 0.92f, -size.width * 0.25f),
            )
            drawCircle(
                color = bodyGlow,
                radius = size.width * 0.7f,
                center = Offset(-size.width * 0.1f, size.height * 0.55f),
            )
        }
    }
}

/** Section label used above lazy groups that are not wrapped in a [SegmentedColumn]. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(start = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact metric tile used by the dashboard headers. */
@Composable
fun XfStatTile(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .background(scheme.surfaceBright)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Floating bottom dock: a solid capsule with a single gradient "pill" that slides between tabs.
 *
 * Deliberately avoids backdrop blur, lensing and tilt sensing from the previous liquid-glass bar:
 * one animated offset plus four static rows is all it needs, so page transitions stay cheap.
 */
@Composable
fun XiaoFengDock(
    items: List<XfDockItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(30.dp)
    val progress by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "dockIndicator",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp),
        shape = shape,
        color = scheme.surfaceContainerHigh.copy(alpha = 0.97f),
        contentColor = scheme.onSurface,
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.16f)),
        shadowElevation = 12.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
        ) {
            val tabWidth = maxWidth / items.size.toFloat()

            Box(
                Modifier
                    .offset(x = tabWidth * progress)
                    .width(tabWidth)
                    .fillMaxSize()
                    .padding(horizontal = 3.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.horizontalGradient(listOf(scheme.primary, scheme.tertiary))),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                items.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(
                                role = Role.Tab,
                                onClick = { onSelected(index) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) scheme.onPrimary
                            else scheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}
