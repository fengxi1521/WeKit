package com.xiao.feng.ui.content.m3

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * iOS-style continuous corners (a squircle rather than a plain circular arc).
 *
 * [smoothing] controls how gradually the rounded part blends into the edge; Apple's icons sit
 * around 0.6. Every group card, dialog, panel and tab uses this shape so the module shares the
 * same curvature as iOS 27's Liquid Glass surfaces.
 */
class ContinuousRoundedCornerShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
    private val smoothing: Float = DEFAULT_SMOOTHING,
) : Shape {

    constructor(radius: Dp, smoothing: Float = DEFAULT_SMOOTHING) :
            this(radius, radius, radius, radius, smoothing)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }

        val limit = size.minDimension / 2f
        fun Dp.clamped(): Float = with(density) { toPx() }.coerceIn(0f, limit)

        val rounding = listOf(
            topStart.clamped(),
            topEnd.clamped(),
            bottomEnd.clamped(),
            bottomStart.clamped(),
        ).map { radius ->
            if (radius <= 0f) CornerRounding.Unrounded else CornerRounding(radius, smoothing)
        }

        val polygon = RoundedPolygon(
            floatArrayOf(
                0f, 0f,
                size.width, 0f,
                size.width, size.height,
                0f, size.height,
            ),
            CornerRounding.Unrounded,
            rounding,
        )
        return Outline.Generic(polygon.toPath().asComposePath())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ContinuousRoundedCornerShape) return false
        return topStart == other.topStart &&
                topEnd == other.topEnd &&
                bottomEnd == other.bottomEnd &&
                bottomStart == other.bottomStart &&
                smoothing == other.smoothing
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        result = 31 * result + smoothing.hashCode()
        return result
    }

    companion object {
        const val DEFAULT_SMOOTHING = 0.6f
    }
}
