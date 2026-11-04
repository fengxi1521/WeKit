package com.xiao.feng.features.items.entertain

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.WeLogger
import java.lang.reflect.Field
import java.time.LocalDate
import java.time.Month
import java.util.WeakHashMap

/**
 * April 1 easter egg: avatars spin and every TextView's text is painted with a moving rainbow
 * gradient. It enables itself only on April 1; turning the switch off that day records a
 * surrender flag so it stays off afterwards.
 */
object AprilFools : SwitchFeature() {

    override val technicalId = "愚人节彩蛋"
    override val nameRes = R.string.feature_april_fools_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_april_fools_description

    private const val TAG = "AprilFools"
    private const val KEY_SURRENDER = "april_fools_surrender"

    private const val PIXELS_PER_FRAME = 10f

    private val RAINBOW_COLORS = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    private val viewStateMap = WeakHashMap<View, TextViewAnimationState>()

    private data class TextViewAnimationState(val matrix: Matrix, var offset: Float)

    private lateinit var noMeasuredTextProp: Field
    private lateinit var noMeasuredPaintProp: Field

    override val shouldEnableOnStartup: Boolean
        get() = LocalDate.now().isAprilFools && !KvStore.getBoolOrDef(KEY_SURRENDER, false)

    override fun onEnable() {
        runCatching {
            ImageView::class.reflekt()
                .firstConstructor { parameterCount = 4 }
                .hookAfter { applyRotation(thisObject as View) }
        }.onFailure { WeLogger.w(TAG, "ImageView constructor hook unavailable", it) }

        runCatching {
            "com.tencent.mm.ui.widget.QImageView".toClass().reflekt()
                .firstConstructor()
                .hookAfter { applyRotation(thisObject as View) }
        }.onFailure { WeLogger.d(TAG, "QImageView not present on this host") }

        TextView::class.reflekt().firstMethod { name = "onDraw" }.hookBefore {
            val textView = thisObject as TextView
            applyRainbowEffect(textView, textView.text, textView.paint)
        }

        runCatching {
            "com.tencent.mm.ui.base.NoMeasuredTextView".toClass().reflekt()
                .firstMethod { name = "onDraw" }
                .hookBefore {
                    val view = thisObject as View
                    if (!::noMeasuredTextProp.isInitialized) {
                        noMeasuredTextProp = view.reflekt()
                            .firstField { name = "mText" }.self.makeAccessible()
                        noMeasuredPaintProp = view.reflekt()
                            .firstField { type = TextPaint::class }.self.makeAccessible()
                    }
                    applyRainbowEffect(
                        view,
                        noMeasuredTextProp.get(view) as CharSequence,
                        noMeasuredPaintProp.get(view) as TextPaint,
                    )
                }
        }.onFailure { WeLogger.d(TAG, "NoMeasuredTextView not present on this host") }
    }

    override fun onDisable() {
        if (LocalDate.now().isAprilFools) {
            KvStore.putBool(KEY_SURRENDER, true)
        }
    }

    private fun applyRotation(view: View) {
        view.post {
            ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun applyRainbowEffect(view: View, text: CharSequence, paint: TextPaint) {
        val width = view.measuredWidth.toFloat()
        if (width <= 0f || text.isEmpty()) return

        val state = viewStateMap.getOrPut(view) { TextViewAnimationState(Matrix(), 0f) }
        val rainbowWidth = width.coerceAtLeast(400f)
        val shader = LinearGradient(
            0f, 0f, rainbowWidth, 0f,
            RAINBOW_COLORS, null, Shader.TileMode.REPEAT
        )

        state.offset += PIXELS_PER_FRAME
        if (state.offset > rainbowWidth) state.offset -= rainbowWidth

        state.matrix.setTranslate(state.offset, 0f)
        shader.setLocalMatrix(state.matrix)
        paint.shader = shader

        view.postInvalidateDelayed(60)
    }
}

private val LocalDate.isAprilFools get() = month == Month.APRIL && dayOfMonth == 1
