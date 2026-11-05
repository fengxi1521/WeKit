package com.xiao.feng.features.items.miniapps

import android.app.Activity
import com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.TargetProcess
import org.json.JSONObject

object RemoveSplashAds : SwitchFeature(), IResolveDex {

    override val technicalId = "移除开屏广告"
    override val nameRes = R.string.feature_remove_splash_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_splash_ads_description

    private val methodIsAdContact by dexMethod()
    private val methodCheckCanShowAd by dexMethod()
    // JsApiAdOperateWXData 的 CGI 回调，作为兜底：阻止广告数据回调写入 worker，
    // 冷启动没有预加载数据时 worker 就拿不到广告素材。
    private val methodAdDataCallback by dexMethod()
    // AppBrandSplashAdLogic.sendShouldShowAdWhenLaunchIfNeed: 冷启动走 displayMode=1,
    // 热启动/切回前台走 displayMode=2。这里只拦 2，避免把启动以外的逻辑一起截断。
    private val methodSendShouldShowAd by dexMethod()
    // JsApiShowSplashAd 是广告 worker 最终把开屏广告显示出来的回调。把 show 改成 false
    // 再放行原方法，微信会走 "not show splash ad" 分支并调用 closeSplashAdImmediately()
    // 正常收起加载页；直接提前 return 反而会让加载页卡住不消失。
    private val methodShowSplashAd by dexMethod()

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.APPBRAND)

    override fun onEnable() {
        methodIsAdContact.hookBefore {
            result = false
        }

        methodCheckCanShowAd.hookBefore {
            result = false
        }

        methodAdDataCallback.hookBefore {
            result = null
        }

        methodSendShouldShowAd.hookBefore {
            if (args.getOrNull(0) as? Int == 2) {
                result = null
            }
        }

        methodShowSplashAd.hookBefore {
            (args.getOrNull(1) as? JSONObject)?.put("show", false)
        }

        AppBrandAdUI::class.java.hookBeforeOnCreate {
            val activity = thisObject as Activity
            activity.finish()
            result = null
        }
    }
}
