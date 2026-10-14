package com.xiao.feng.features.items.chat

import android.widget.ImageView
import androidx.core.net.toUri
import com.xiao.feng.R
import com.xiao.feng.dexkit.abc.IResolveDex
import com.xiao.feng.dexkit.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.WeLogger

/**
 * Blocks read-receipt tracking pixels embedded as Finder feed thumbnails.
 *
 * [ReadReceipts] places its pixel URL in both thumbUrl and coverUrl. WeChat passes the selected
 * URL through FinderLoaderApi before loading or prefetching it, so skipping this method prevents
 * either path from reaching the tracking server.
 */
object AntiReadReceipts : SwitchFeature(), IResolveDex {

    override val technicalId = "反已读追踪"
    override val nameRes = R.string.feature_anti_read_receipts_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_anti_read_receipts_description

    private val methodLoadFinderImage by dexMethod {
        matcher {
            usingEqStrings("FinderLoaderApi", "#loadImage url=")
            paramTypes(String::class.java, ImageView::class.java, null)
            returnType("void")
        }
    }

    override fun onEnable() {
        methodLoadFinderImage.hookBefore {
            val url = args[0] as? String? ?: return@hookBefore
            if (url.toUri().path?.endsWith("/pixel") == true) {
                WeLogger.d("AntiReadReceipts", "blocked request to $url")
                result = null
            }
        }
    }
}
