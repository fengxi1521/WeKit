package com.xiao.feng.features.items.chat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.xiao.feng.utils.android.isDarkMode
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Shared visual treatment for floating chat cards.
 *
 * Light mode keeps WeChat's own backgrounds intact. Dark mode needs an explicit surface and a
 * hairline border because Android elevation is barely visible on near-black chat backgrounds.
 *
 * Glass mode replaces the opaque surface with a translucent gradient plus a brighter rim, which
 * lets the conversation behind the card subtly show through and reads as frosted glass.
 */
object FloatingChatCardVisuals {

    private const val DARK_SURFACE_COLOR = 0xFF242424.toInt()
    private const val DARK_STROKE_COLOR = 0x24FFFFFF
    private const val DARK_STROKE_WIDTH_DP = 1

    /** Light glass: near-white top fading to a slightly cooler, more transparent bottom. */
    private val LIGHT_GLASS_COLORS = intArrayOf(0xD9FFFFFF.toInt(), 0xA8EFEFF4.toInt())
    private const val LIGHT_GLASS_RIM = 0x99FFFFFF.toInt()

    /** Dark glass: charcoal top fading into a deeper, more transparent bottom. */
    private val DARK_GLASS_COLORS = intArrayOf(0xD92C2C2E.toInt(), 0xA8171719.toInt())
    private const val DARK_GLASS_RIM = 0x40FFFFFF

    private data class AppliedStyle(
        val cornerRadiusDp: Int,
        val strokeWidthPx: Int,
        val glass: Boolean,
    )

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val appliedBackgrounds = WeakHashMap<View, Drawable>()
    private val appliedStyles = WeakHashMap<View, AppliedStyle>()

    fun applySurface(view: View, cornerRadiusDp: Int, glass: Boolean) {
        if (!glass) {
            applyDarkSurface(view, cornerRadiusDp)
            return
        }

        if (!originalBackgrounds.containsKey(view)) {
            originalBackgrounds[view] = view.background
        }

        val density = view.resources.displayMetrics.density
        val strokeWidthPx = (DARK_STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val style = AppliedStyle(cornerRadiusDp, strokeWidthPx, glass = true)
        val appliedBackground = appliedBackgrounds[view]
        if (appliedStyles[view] == style && view.background === appliedBackground) return

        val dark = view.context.isDarkMode
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * density
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setColors(if (dark) DARK_GLASS_COLORS else LIGHT_GLASS_COLORS)
            setStroke(strokeWidthPx, if (dark) DARK_GLASS_RIM else LIGHT_GLASS_RIM)
        }
        view.background = background
        appliedBackgrounds[view] = background
        appliedStyles[view] = style
    }

    fun applyDarkSurface(view: View, cornerRadiusDp: Int) {
        if (!view.context.isDarkMode) {
            restoreOriginalBackground(view)
            return
        }

        if (!originalBackgrounds.containsKey(view)) {
            originalBackgrounds[view] = view.background
        }

        val density = view.resources.displayMetrics.density
        val strokeWidthPx = (DARK_STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val style = AppliedStyle(cornerRadiusDp, strokeWidthPx, glass = false)
        val appliedBackground = appliedBackgrounds[view]
        if (appliedStyles[view] == style && view.background === appliedBackground) return

        val radiusPx = cornerRadiusDp * density
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(DARK_SURFACE_COLOR)
            setStroke(strokeWidthPx, DARK_STROKE_COLOR)
        }
        view.background = background
        appliedBackgrounds[view] = background
        appliedStyles[view] = style
    }

    private fun restoreOriginalBackground(view: View) {
        if (!originalBackgrounds.containsKey(view)) return
        val appliedBackground = appliedBackgrounds[view]
        if (view.background === appliedBackground) {
            view.background = originalBackgrounds[view]
        }
        originalBackgrounds.remove(view)
        appliedBackgrounds.remove(view)
        appliedStyles.remove(view)
    }
}
