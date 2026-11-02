package com.xiao.feng.features.items.moments

import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.features.api.ui.WeMomentsContextMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.ui.utils.VisibilityIcon
import com.xiao.feng.utils.WeLogger

/**
 * Adds a "view all their Moments" entry to the long-press menu of a Moment. It opens the author's
 * personal album (SnsUserUI), which lists every Moment the viewer is allowed to see, keeping the
 * author's pinned posts at the top.
 */
object OpenAuthorMoments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    override val technicalId = "查看作者全部朋友圈"
    override val nameRes = R.string.feature_open_author_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_open_author_moments_description
    override val defaultEnabled = true

    private const val TAG = "OpenAuthorMoments"

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777021,
                localizedMomentsString(R.string.moments_open_author_menu),
                VisibilityIcon,
                { context, _ -> WeMomentsApi.getOwnerWxId(context)?.isNotEmpty() == true },
            ) { context ->
                val wxId = WeMomentsApi.getOwnerWxId(context)
                if (wxId.isNullOrEmpty()) {
                    WeLogger.w(TAG, "failed to resolve the Moments author")
                } else {
                    WeApi.openMoments(context.activity, wxId)
                }
            }
        )
    }
}
