package com.xiao.feng.features.items.moments

import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature

object DisableVideosAutoPlay : SwitchFeature(), IResolveDex {

    override val technicalId = "禁止自动播放视频"
    override val nameRes = R.string.feature_disable_videos_auto_play_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_disable_videos_auto_play_description

    private val methodCheckAutoPlay by dexMethod()

    private val methodImproveAutoPlayInvoke by dexMethod()

    override fun onEnable() {
        methodCheckAutoPlay.hookBefore {
            result = false
        }
        methodImproveAutoPlayInvoke.hookBefore {
            result = false
        }
    }
}
