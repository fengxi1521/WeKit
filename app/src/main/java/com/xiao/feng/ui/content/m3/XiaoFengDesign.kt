package com.xiao.feng.ui.content.m3

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Search

/**
 * One tab shown in [XfTabBar].
 */
data class XfTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)

/**
 * iOS 27 Liquid Glass page backdrop.
 *
 * A porcelain wash, three wide ambient blooms and a diagonal specular streak. Blooms are pure
 * gradients with no hard edges, so translucent cards read as frosted glass without an actual
 * backdrop blur pass. Everything is described once inside [drawWithCache].
 */
@Composable
fun Modifier.xfPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return drawWithCache {
        val wash = Brush.verticalGradient(
            0f to if (dark) Color(0xFF060810) else Color(0xFFF7F8FC),
            0.42f to if (dark) Color(0xFF0B1020) else Color(0xFFF0F3F9),
            1f to if (dark) Color(0xFF05060A) else Color(0xFFE9EEF6),
        )
        val bloomPrimary = Brush.radialGradient(
            colors = listOf(
                scheme.primary.copy(alpha = if (dark) 0.34f else 0.20f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.96f, size.height * 0.02f),
            radius = size.width * 1.0f,
        )
        val bloomLavender = Brush.radialGradient(
            colors = listOf(
                Color(0xFFAF52DE).copy(alpha = if (dark) 0.18f else 0.10f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.02f, size.height * 0.38f),
            radius = size.width * 0.86f,
        )
        val bloomTeal = Brush.radialGradient(
            colors = listOf(
                Color(0xFF30B0C7).copy(alpha = if (dark) 0.16f else 0.10f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.82f, size.height * 1.02f),
            radius = size.width * 0.78f,
        )
        // Diagonal light sweep across the top third, like light grazing a glass sheet.
        val sheen = Brush.linearGradient(
            0f to Color.White.copy(alpha = if (dark) 0.05f else 0.52f),
            0.32f to Color.White.copy(alpha = if (dark) 0.015f else 0.15f),
            1f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(size.width * 1.15f, size.height * 0.9f),
        )
        onDrawBehind {
            drawRect(wash)
            drawRect(brush = bloomPrimary)
            drawRect(brush = bloomLavender)
            drawRect(brush = bloomTeal)
            drawRect(brush = sheen)
        }
    }
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
    val glassBrush = liquidGlassBrush()
    val glassShadow = liquidGlassShadow()
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (navigation != null || actions != null) {
            val barShape = ContinuousRoundedCornerShape(28.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = barShape,
                        ambientColor = glassShadow.copy(alpha = 0.30f),
                        spotColor = glassShadow,
                    )
                    .clip(barShape)
                    .background(glassBrush)
                    .border(0.5.dp, liquidGlassDockRimColor(), barShape)
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
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            lineHeight = 37.sp,
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
        Spacer(Modifier.height(18.dp))
    }
}

/**
 * Chapter label used above lazy groups, mirroring the plain secondary caption of iOS settings.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp, start = 4.dp),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.6.sp),
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * Metric tile for the dashboard/bento areas: a Liquid Glass cell with a continuous-corner
 * icon badge, a large figure and a compact label.
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
    val shape = ContinuousRoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .height(height)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = liquidGlassShadow().copy(alpha = 0.26f),
                spotColor = liquidGlassShadow(),
            )
            .clip(shape)
            .background(liquidGlassBrush())
            .border(width = 0.5.dp, color = liquidGlassRimColor(), shape = shape)
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
                    .clip(ContinuousRoundedCornerShape(10.dp))
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
 * Capsule search field, styled like the iOS search bar: a quiet translucent pill with a leading
 * glyph and an inline clear action.
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
    val shape = ContinuousRoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(liquidGlassBrush())
            .border(0.5.dp, liquidGlassRimColor(), shape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Search,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
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
 * Floating Liquid Glass dock, matching the iOS 27 tab bar: a translucent capsule lifted off the
 * page where the selected tab becomes an accent-tinted inner capsule and reveals its label.
 */
@Composable
fun XfTabBar(
    tabs: List<XfTab>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val shape = ContinuousRoundedCornerShape(32.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 10.dp)
            .height(64.dp)
            .shadow(
                elevation = 22.dp,
                shape = shape,
                ambientColor = liquidGlassShadow().copy(alpha = 0.34f),
                spotColor = liquidGlassShadow(),
            )
            .clip(shape)
            .background(liquidGlassBrush())
            .border(0.5.dp, liquidGlassDockRimColor(), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val tint by animateColorAsState(
                targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
                label = "tabTint",
            )
            val inner by animateColorAsState(
                targetValue = if (selected) scheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                label = "tabInner",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(ContinuousRoundedCornerShape(26.dp))
                    .background(inner)
                    .clickable(role = Role.Tab, onClick = { onSelected(index) }),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
                if (selected) {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        color = tint,
                    )
                }
            }
        }
    }
}

/**
 * Reusable translucent glass surface for one-off panels (hero, banners, dashboard cells).
 */
@Composable
fun XfPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = ContinuousRoundedCornerShape(26.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = liquidGlassShadow().copy(alpha = 0.26f),
                spotColor = liquidGlassShadow(),
            )
            .clip(shape)
            .background(liquidGlassBrush())
            .border(width = 0.5.dp, color = liquidGlassRimColor(), shape = shape),
        content = content,
    )
}
