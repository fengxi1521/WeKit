package com.xiao.feng.ui.content.m3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * iOS 27's Liquid Glass appearance slider: 0 is ultra clear, 100 is fully tinted.
 * Provided by [com.xiao.feng.ui.utils.theme.ModuleTheme] and read by every glass surface.
 */
val LocalLiquidGlassLevel = compositionLocalOf { 50 }

@Composable
private fun levelFraction(): Float = LocalLiquidGlassLevel.current.coerceIn(0, 100) / 100f

@Composable
fun isDarkGlass(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * Tint used by cards, rows, panels and the floating dock. Clear glass stays close to the neutral
 * surface, tinted glass mixes in more of the accent, matching iOS 27's live preview.
 */
@Composable
fun liquidGlassSurfaceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val level = levelFraction()
    val base = if (isDarkGlass()) scheme.surfaceContainerHigh else Color.White
    val tinted = lerp(base, scheme.primary, 0.04f + 0.22f * level)
    return tinted.copy(alpha = 0.30f + 0.52f * level)
}

/**
 * Opaque enough for surfaces that carry readable text and sit above a scrim (dialogs, sheets).
 */
@Composable
fun liquidGlassDialogColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val level = levelFraction()
    val base = if (isDarkGlass()) scheme.surfaceContainerHigh else Color.White
    val tinted = lerp(base, scheme.primary, 0.03f + 0.14f * level)
    return tinted.copy(alpha = 0.92f + 0.07f * level)
}

/**
 * Vertical light-to-less-light glass gradient: the top edge catches light, the bottom falls away.
 */
@Composable
fun liquidGlassBrush(): Brush {
    val base = liquidGlassSurfaceColor()
    val lift = if (isDarkGlass()) 0.05f else 0.10f
    return Brush.verticalGradient(
        listOf(
            base.copy(alpha = (base.alpha + lift).coerceAtMost(0.98f)),
            base,
            base.copy(alpha = (base.alpha - lift * 0.6f).coerceAtLeast(0.18f)),
        ),
    )
}

/**
 * One-pixel rim that makes the material read as glass instead of a flat translucent fill.
 */
@Composable
fun liquidGlassRimColor(): Color = if (isDarkGlass()) {
    Color.White.copy(alpha = 0.14f)
} else {
    Color.White.copy(alpha = 0.65f)
}

/**
 * Stronger rim for the floating navigation dock, where the highlight is part of the iOS look.
 */
@Composable
fun liquidGlassDockRimColor(): Color = Color.White.copy(
    alpha = if (isDarkGlass()) 0.20f else 0.62f,
)

/** Soft ambient shadow shared by all floating glass surfaces. */
@Composable
fun liquidGlassShadow(): Color = if (isDarkGlass()) {
    Color.Black.copy(alpha = 0.55f)
} else {
    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
}

/** Hairline separator used inside a segmented glass card. */
@Composable
fun liquidGlassDividerColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
    alpha = if (isDarkGlass()) 0.22f else 0.16f,
)

/** Corner radius for grouped list cards. */
val GlassCardRadius = CornerRadius

/** Corner radius for dialogs and elevated sheets. */
val GlassDialogRadius = 30.dp
