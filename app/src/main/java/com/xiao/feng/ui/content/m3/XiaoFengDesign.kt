package com.xiao.feng.ui.content.m3

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * Static page backdrop: a soft vertical wash, a thin accent band across the header and one large
 * faint ring in the top-right corner.
 *
 * Everything is drawn from a cached description inside [drawWithCache], so scrolling never
 * allocates or re-reads theme state per frame. The look deliberately replaces the old blurred
 * backdrop with cheap geometry: a wash, one rectangle and one stroked circle.
 */
@Composable
fun Modifier.xfPageBackground(): Modifier {
    val scheme = MaterialTheme.colorScheme
    return drawWithCache {
        val wash = Brush.verticalGradient(
            0f to scheme.surfaceContainerLow,
            0.45f to scheme.surfaceContainer,
            1f to scheme.surfaceContainerLowest,
        )
        val bandHeight = 190.dp.toPx()
        val band = Brush.horizontalGradient(
            0f to scheme.primary.copy(alpha = 0.075f),
            0.7f to scheme.tertiary.copy(alpha = 0.035f),
            1f to Color.Transparent,
            startX = 0f,
            endX = size.width,
        )
        val ringColor = scheme.primary.copy(alpha = 0.07f)
        val ringStroke = Stroke(width = 2.dp.toPx())
        onDrawBehind {
            drawRect(wash)
            drawRect(
                brush = band,
                size = Size(size.width, bandHeight),
            )
            drawCircle(
                color = ringColor,
                radius = size.width * 0.46f,
                center = Offset(size.width * 1.04f, bandHeight * 0.55f),
                style = ringStroke,
            )
        }
    }
}

/**
 * Editorial page masthead used on every top-level screen.
 *
 * It replaces the collapsing large app bar: a small overline, an outsized title, an optional
 * subtitle, then a rule with a short accent segment. Back and page actions live in the slim row
 * above the title. Everything scrolls with the page, so content gets the full viewport.
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
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
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
            Spacer(Modifier.height(4.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.4.sp,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(30.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(scheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(scheme.outline.copy(alpha = 0.28f)),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Chapter label used above lazy groups: a small accent dot, the title in tracked-out caps and a
 * hairline that runs to the right edge. Unlike the old bare label it reads as a chapter marker.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.26f)),
        )
    }
}

/**
 * Metric tile for the dashboard/bento areas: an outlined cell with a large figure, a compact
 * label and an optional icon. No filled card, no gradient — the outline keeps it light and lets
 * the page wash show through.
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
    Column(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerLowest.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = scheme.outline.copy(alpha = 0.22f),
                shape = RoundedCornerShape(18.dp),
            )
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            color = scheme.onSurface,
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
 * Flat command-bar search: icon, inline placeholder and an underline. Used by the feature index
 * and the license list so both screens share the same search affordance.
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
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Search,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.62f),
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
                    )
                }
            }
        }
        HorizontalDivider(color = scheme.outline.copy(alpha = 0.22f))
    }
}

/**
 * Edge-to-edge bottom tab bar. It replaces the floating liquid-glass capsule: a flat strip with a
 * hairline top border, a sliding 24dp indicator and plain icon/label tabs. No shadow, no blur and
 * no rounded container, so it stays cheap during pager swipes.
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
    val progress by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
        label = "tabIndicator",
    )

    Column(modifier = modifier.fillMaxWidth().background(scheme.surfaceContainer).navigationBarsPadding()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outline.copy(alpha = 0.16f)),
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(58.dp),
        ) {
            val tabWidth = maxWidth / tabs.size.toFloat()
            Box(
                Modifier
                    .offset(x = tabWidth * progress + (tabWidth - 24.dp) / 2)
                    .width(24.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                    .background(scheme.primary),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable(role = Role.Tab, onClick = { onSelected(index) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (selected) tab.selectedIcon else tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.72f),
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = tab.label,
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reusable outlined surface for small one-off panels (hero, banners, dashboard cells) that should
 * not look like another settings group.
 */
@Composable
fun XfPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerLowest.copy(alpha = 0.7f))
            .border(
                width = 1.dp,
                color = scheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp),
            ),
        content = content,
    )
}
