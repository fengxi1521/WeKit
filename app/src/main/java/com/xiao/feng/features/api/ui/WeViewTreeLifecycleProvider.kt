package com.xiao.feng.features.api.ui

import android.app.Activity
import com.tencent.mm.ui.LauncherUI
import com.xiao.feng.R
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.utils.LifecycleOwnerProvider
import com.xiao.feng.ui.utils.rootView
import com.xiao.feng.ui.utils.setLifecycleOwner

object WeViewTreeLifecycleProvider : ApiFeature() {

    override val technicalId = "Compose 生命周期提供方"
    override val nameRes = R.string.feature_we_view_tree_lifecycle_provider_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    override fun onEnable() {
        LauncherUI::class.hookAfterOnCreate {
            val activity = thisObject as Activity

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner

            val decorView = activity.window.decorView
            decorView.setLifecycleOwner(lifecycleOwner)
            activity.rootView.setLifecycleOwner(lifecycleOwner)
        }
    }
}
