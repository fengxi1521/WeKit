package com.xiao.feng.features.items.moments

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * Hides rows on WeChat's short, non-scrolling preference lists by walking the already-rendered
 * ListView children after `onResume` and matching their visible text. Nothing is changed in the
 * data model, so re-entering the page after turning the switch off restores the rows.
 */
object HideDiscoverMomentsEntry : SwitchFeature() {

    override val technicalId = "发现页朋友圈入口隐藏"
    override val nameRes = R.string.feature_hide_discover_moments_entry_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_hide_discover_moments_entry_description

    override fun onEnable() {
        hookListFilter("com.tencent.mm.ui.FindMoreFriendsUI") { label -> label == "朋友圈" }
    }
}

object HideMomentsGroupTag : SwitchFeature() {

    override val technicalId = "朋友圈分组图标隐藏"
    override val nameRes = R.string.feature_hide_moments_group_tag_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_hide_moments_group_tag_description

    private val PAGES = listOf(
        "com.tencent.mm.plugin.sns.ui.SnsTagDetailUI",
        "com.tencent.mm.plugin.sns.ui.SnsTagPartlyUI",
    )

    override fun onEnable() {
        PAGES.forEach { page ->
            hookListFilter(page) { label -> label.contains("分组") }
        }
    }
}

private const val TAG = "MomentsEntryHider"

private fun SwitchFeature.hookListFilter(className: String, predicate: (String) -> Boolean) {
    val clazz = runCatching { className.toClass() }.getOrElse {
        WeLogger.d(TAG, "$className not present on this host")
        return
    }
    val onResume = runCatching { clazz.reflekt().firstMethod { name = "onResume" } }.getOrElse {
        WeLogger.w(TAG, "$className.onResume is unavailable; row hiding skipped", it)
        return
    }

    onResume.hookAfter {
        val activity = thisObject as Activity
        hideListRows(activity, predicate)
    }
}

private fun hideListRows(activity: Activity, predicate: (String) -> Boolean) {
    val list = activity.findViewById<ViewGroup>(android.R.id.list) ?: return
    for (index in 0 until list.childCount) {
        val row = list.getChildAt(index) ?: continue
        val label = firstText(row)?.trim().orEmpty()
        if (label.isEmpty() || !predicate(label)) continue
        if (row.visibility == View.GONE) continue
        row.visibility = View.GONE
        WeLogger.d(TAG, "hidden row: $label")
    }
}

private fun firstText(view: View): String? {
    if (view is TextView) {
        val text = view.text?.toString()
        if (!text.isNullOrBlank()) return text
    }
    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            firstText(view.getChildAt(index))?.let { return it }
        }
    }
    return null
}
