package com.xiao.feng.features.items.chat

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

private const val TAG_ORIGINAL_BG_LP = 0x7F000001
private const val TAG_ORIGINAL_TOOLBAR_TOP = 0x7F000002
private const val TAG_ORIGINAL_STATUS_BAR_COLOR = 0x7F000003

/**
 * Makes the chat page's status bar fully transparent and extends the chat background behind it.
 * The original window/view state is recorded per Activity so disabling can restore it best-effort.
 */
object ImmersiveStatusBar : SwitchFeature() {

    override val technicalId = "聊天页沉浸式透明状态栏"
    override val nameRes = R.string.feature_immersive_status_bar_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_immersive_status_bar_description

    private val trackedActivities = mutableSetOf<Activity>()

    override fun onEnable() {
        val chattingUi = "com.tencent.mm.ui.chatting.ChattingUI".toClass()
        chattingUi.reflekt().firstMethod { name = "onCreate" }.hookAfter {
            applyImmersiveStatusBar(thisObject as Activity)
        }
        chattingUi.reflekt().firstMethod { name = "onResume" }.hookAfter {
            applyImmersiveStatusBar(thisObject as Activity)
        }
        chattingUi.reflekt().firstMethod { name = "onDestroy" }.hookBefore {
            trackedActivities.remove(thisObject as Activity)
        }
    }

    override fun onDisable() {
        trackedActivities.toList().forEach(::restoreStatusBar)
        trackedActivities.clear()
    }

    private fun applyImmersiveStatusBar(activity: Activity) {
        val window = activity.window ?: return
        saveOriginalStatusBarColor(window)

        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val rootView = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        applyBackgroundExtension(activity, rootView, window)
        applyContentPadding(activity, rootView, window)

        trackedActivities.add(activity)
    }

    private fun applyBackgroundExtension(activity: Activity, rootView: ViewGroup, window: Window) {
        val backgroundView = findChatBackgroundView(rootView) ?: return
        if (backgroundView.getTag(TAG_ORIGINAL_BG_LP) == null) {
            backgroundView.setTag(TAG_ORIGINAL_BG_LP, cloneLayoutParams(backgroundView.layoutParams))
        }

        val lp = backgroundView.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.topMargin = -getStatusBarHeight(activity)
            backgroundView.layoutParams = lp
        }
        backgroundView.fitsSystemWindows = false

        // Re-apply after WeChat's own insets pass settles, otherwise the system may re-add padding.
        window.decorView.post { applyContentPadding(activity, rootView, window) }
    }

    private fun applyContentPadding(activity: Activity, rootView: ViewGroup, window: Window) {
        val toolbar = findChatToolbar(rootView)
        if (toolbar != null) {
            if (toolbar.getTag(TAG_ORIGINAL_TOOLBAR_TOP) == null) {
                toolbar.setTag(TAG_ORIGINAL_TOOLBAR_TOP, toolbar.paddingTop)
            }
            toolbar.setPadding(
                toolbar.paddingLeft,
                getStatusBarHeight(activity),
                toolbar.paddingRight,
                toolbar.paddingBottom,
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
    }

    private fun restoreStatusBar(activity: Activity) {
        val window = activity.window ?: return
        val originalColor = window.decorView.getTag(TAG_ORIGINAL_STATUS_BAR_COLOR) as? Int
        window.statusBarColor = originalColor ?: Color.BLACK

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN).inv()
        }

        val rootView = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val backgroundView = findChatBackgroundView(rootView)
        if (backgroundView != null) {
            val originalLp = backgroundView.getTag(TAG_ORIGINAL_BG_LP)
            if (originalLp is ViewGroup.LayoutParams) {
                backgroundView.layoutParams = originalLp
            }
            backgroundView.setTag(TAG_ORIGINAL_BG_LP, null)
            backgroundView.fitsSystemWindows = true
        }

        val toolbar = findChatToolbar(rootView)
        if (toolbar != null) {
            val originalTop = toolbar.getTag(TAG_ORIGINAL_TOOLBAR_TOP)
            if (originalTop is Int) {
                toolbar.setPadding(
                    toolbar.paddingLeft,
                    originalTop,
                    toolbar.paddingRight,
                    toolbar.paddingBottom,
                )
            }
            toolbar.setTag(TAG_ORIGINAL_TOOLBAR_TOP, null)
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView, null)
        window.decorView.setTag(TAG_ORIGINAL_STATUS_BAR_COLOR, null)
    }

    private fun saveOriginalStatusBarColor(window: Window) {
        if (window.decorView.getTag(TAG_ORIGINAL_STATUS_BAR_COLOR) == null) {
            window.decorView.setTag(TAG_ORIGINAL_STATUS_BAR_COLOR, window.statusBarColor)
        }
    }

    private fun cloneLayoutParams(lp: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? =
        when (lp) {
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp.width, lp.height).apply {
                leftMargin = lp.leftMargin
                topMargin = lp.topMargin
                rightMargin = lp.rightMargin
                bottomMargin = lp.bottomMargin
            }

            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp.width, lp.height)
            else -> null
        }

    private fun findChatBackgroundView(parent: ViewGroup): View? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.background != null && child.isFullSize()) return child
        }
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child is android.widget.ImageView && child.isFullSize()) return child
        }
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.isFullSize()) return child
        }
        return null
    }

    private fun View.isFullSize(): Boolean {
        val lp = layoutParams as? ViewGroup.LayoutParams ?: return false
        return lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.height == ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun findChatToolbar(parent: ViewGroup): View? {
        val suffixes = setOf("ChattingTitleBar", "MMTitleBar", "ActionBarContainer", "Toolbar")
        return findViewByClassSuffix(parent, suffixes)
    }

    private fun findViewByClassSuffix(parent: ViewGroup, suffixes: Set<String>): View? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (suffixes.any { child.javaClass.simpleName.endsWith(it) }) return child
            if (child is ViewGroup) {
                findViewByClassSuffix(child, suffixes)?.let { return it }
            }
        }
        return null
    }

    private fun getStatusBarHeight(activity: Activity): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            activity.resources.getDimensionPixelSize(resourceId)
        } else {
            (24 * activity.resources.displayMetrics.density).toInt()
        }
    }
}
