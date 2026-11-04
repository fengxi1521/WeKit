package com.xiao.feng.features.items.channels

import android.text.StaticLayout
import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible

/**
 * Channels comment scrolling can compute a negative layout width (observed -42), which makes
 * `Layout.<init>` throw `IllegalArgumentException: Layout: -42 < 0` and crash the host. Clamp the
 * builder's width to zero so the offending comment renders as an empty line instead.
 */
object FinderLayoutGuard : SwitchFeature() {

    override val technicalId = "视频号崩溃防御"
    override val nameRes = R.string.feature_finder_layout_guard_name
    override val categoryIds = listOf(FeatureCategoryIds.CHANNELS)
    override val descriptionRes = R.string.feature_finder_layout_guard_description

    private const val TAG = "FinderLayoutGuard"

    override fun onEnable() {
        val widthField = runCatching {
            StaticLayout.Builder::class.java.getDeclaredField("mWidth").makeAccessible()
        }.getOrElse {
            WeLogger.w(TAG, "StaticLayout.Builder.mWidth is unavailable; guard disabled")
            return
        }

        StaticLayout.Builder::class.reflekt()
            .firstMethod { name = "build" }
            .hookBefore {
                val builder = thisObject as StaticLayout.Builder
                val width = widthField.getInt(builder)
                if (width >= 0) return@hookBefore
                widthField.setInt(builder, 0)
                WeLogger.d(TAG, "clamped negative StaticLayout width $width to 0")
            }
    }
}
