package com.xiao.feng.ui.content.m3

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Search
import com.xiao.feng.ui.content.FloatingBottomBar
import com.xiao.feng.ui.content.FloatingBottomBarDefaults
import com.xiao.feng.ui.content.FloatingBottomBarMode
import com.xiao.feng.ui.content.liquid.InnerShadow
import com.xiao.feng.ui.content.liquid.innerShadow
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * One tab shown in [XfTabBar].
 */
data class XfTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * Violet-aurora page backdrop.
 *
 * A vertical wash plus three large radial blooms (primary / tertiary / secondary) and a sparse
 * field of light dust. Everything is described once inside [drawWithCache], so the page pays for
 * three gradients and a couple dozen tiny circles at draw time — no blur, no per-frame work.
 */
@Composable
fun Modifier.xfPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    return drawWithCache {
        val wash = Brush.verticalGradient(
            0f to scheme.surfaceContainerLowest,
            0.4f to scheme.surfaceContainer,
            1f to scheme.surfaceContainerLow,
        )
        val bloomPrimary = Brush.radialGradient(
            colors = listOf(
                scheme.primary.copy(alpha = if (dark) 0.42f else 0.26f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.94f, -size.height * 0.04f),
            radius = size.width * 1.05f,
        )
        val bloomTertiary = Brush.radialGradient(
            colors = listOf(
                scheme.tertiary.copy(alpha = if (dark) 0.30f else 0.17f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.02f, size.height * 0.42f),
            radius = size.width * 0.8f,
        )
        val bloomSecondary = Brush.radialGradient(
            colors = listOf(
                scheme.secondary.copy(alpha = if (dark) 0.18f else 0.10f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.72f, size.height * 1.04f),
            radius = size.width * 0.74f,
        )
        val dust = List(24) { index ->
            val x = ((index * 73 + 11) % 97) / 97f
            val y = ((index * 47 + 23) % 101) / 101f
            Offset(x * size.width, y * size.height)
        }
        val dustColor = scheme.primary.copy(alpha = if (dark) 0.20f else 0.11f)
        val dustRadius = 1.3.dp.toPx()
        onDrawBehind {
            drawRect(wash)
            drawRect(brush = bloomPrimary)
            drawRect(brush = bloomTertiary)
            drawRect(brush = bloomSecondary)
            dust.forEach { drawCircle(dustColor, dustRadius, it) }
        }
    }
}

/**
 * Liquid-glass surface used by the module chrome.
 *
 * WCX's panels read as a single piece of curved glass: a translucent violet tint, a diagonal
 * specular sheen across the top-left, a bright rim that fades toward the bottom-right and a
 * soft inner shadow hugging the top edge. This draws all of that in one cached pass and samples
 * no backdrop, so it stays cheap enough for scrolling lists (unlike a full refraction lens).
 */
@Composable
fun Modifier.xfGlassSurface(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
    rim: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
): Modifier {
    val dark = isSystemInDarkTheme()
    val rimBrush = Brush.linearGradient(
        0f to Color.White.copy(alpha = if (dark) 0.24f else 0.62f),
        0.42f to rim,
        1f to Color.Transparent,
    )
    var surface = this
        .clip(shape)
        .background(tint, shape)
        .drawWithCache {
            val sheen = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.10f else 0.32f),
                    Color.Transparent,
                ),
                start = Offset.Zero,
                end = Offset(size.width * 1.1f, size.height * 0.85f),
            )
            onDrawBehind { drawRect(brush = sheen) }
        }
        .border(BorderStroke(1.dp, rimBrush), shape)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        surface = surface.innerShadow(shape) {
            InnerShadow(
                radius = 12.dp,
                offset = DpOffset(0.dp, 6.dp),
                color = Color.Black.copy(alpha = if (dark) 0.24f else 0.10f),
            )
        }
    }
    return surface
}

/**
 * Page masthead used on every top-level screen.
 *
 * When the page has a back affordance or actions, they live in a floating translucent capsule
 * above the title instead of a full-width app bar. The overline is a tinted capsule, and the
 * closing rule is a violet-to-transparent gradient. Everything scrolls with the page.
 */
@Composable
fun XfMasthead(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (navigation != null || actions != null) {
            val barShape = RoundedCornerShape(28.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        elevation = 10.dp,
                        shape = barShape,
                        ambientColor = scheme.primary.copy(alpha = 0.20f),
                        spotColor = scheme.primary.copy(alpha = 0.28f),
                    )
                    .xfGlassSurface(barShape)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigation?.invoke()
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    actions?.invoke(this)
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
        if (eyebrow != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 38.sp,
            color = scheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(scheme.primary, scheme.tertiary, Color.Transparent),
                    ),
                ),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Chapter label used above lazy groups: a tinted capsule followed by a violet hairline.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(scheme.primary.copy(alpha = 0.14f))
                .padding(horizontal = 11.dp, vertical = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                color = scheme.primary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(scheme.primary.copy(alpha = 0.16f)),
        )
    }
}

/**
 * Metric tile for the dashboard/bento areas: a translucent violet-rimmed glass cell with a
 * rounded icon badge, a large figure and a compact label.
 */
@Composable
fun XfStatTile(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    height: Dp = 104.dp,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .height(height)
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = scheme.primary.copy(alpha = 0.16f),
                spotColor = scheme.primary.copy(alpha = 0.22f),
            )
            .xfGlassSurface(shape)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(50))
                    .background(scheme.primary.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            color = scheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Compact outlined key/value cell used by the dashboard's spec board. Values are set in a mono
 * face so the board reads like a device label rather than another settings row.
 */
@Composable
fun XfSpecRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Capsule search field: a translucent pill with a leading glyph and an inline clear action.
 * Used by the feature index and the license list.
 */
@Composable
fun XfSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .xfGlassSurface(shape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Search,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Close,
                    contentDescription = clearContentDescription,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/**
 * Floating liquid-glass dock (WCX / Liquid Glass language).
 *
 * Instead of painting a solid gradient pill, [FloatingBottomBar] samples the page behind the bar
 * through a refraction lens and renders the selected tab as a second glass pill that travels with
 * the drag. The container stays violet-tinted so the module keeps its purple identity, and the
 * label only appears on the selected tab.
 */
@Composable
fun XfTabBar(
    tabs: List<XfTab>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme

    FloatingBottomBar(
        items = tabs,
        selectedIndex = { selectedIndex },
        onSelected = onSelected,
        backdrop = backdrop,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        mode = FloatingBottomBarMode.LiquidGlass,
        colors = FloatingBottomBarDefaults.colors(
            containerColor = scheme.primaryContainer,
            indicatorColor = scheme.primary,
            contentColor = scheme.onSurfaceVariant,
            activeContentColor = scheme.primary,
        ),
        liquidGlassBlurRadius = 4.dp,
        iconContent = { tab, index, previewSelected ->
            Icon(
                imageVector = if (index == selectedIndex || previewSelected) {
                    tab.selectedIcon
                } else {
                    tab.icon
                },
                contentDescription = tab.label,
                modifier = Modifier.size(23.dp),
            )
        },
        labelContent = { tab, index ->
            if (index == selectedIndex) {
                Text(
                    text = tab.label,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    )
}

/**
 * Reusable translucent glass surface for one-off panels (hero, banners, dashboard cells).
 */
@Composable
fun XfPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = scheme.primary.copy(alpha = 0.16f),
                spotColor = scheme.primary.copy(alpha = 0.22f),
            )
            .xfGlassSurface(shape),
        content = content,
    )
}
