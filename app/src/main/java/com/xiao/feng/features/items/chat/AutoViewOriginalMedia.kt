package com.xiao.feng.features.items.chat

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import java.util.WeakHashMap

object AutoViewOriginalMedia : SwitchFeature(), IResolveDex {

    override val technicalId = "自动查看原图"
    override val nameRes = R.string.feature_auto_view_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_view_original_media_description

    private const val MEDIA_DOWNLOAD_TEXT_CLASS =
        "com.tencent.mm.plugin.media.view.download.MediaDownloadText"

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()
    private val classMediaGalleryVideoBottomBarLayer by dexClass()
    private val methodUpdateMediaGalleryVideoOriginButton by dexMethod()
    private val classMediaGalleryChatLiveBottomBarLayer by dexClass()
    private val methodBindMediaGalleryChatLiveBottomBar by dexMethod()

    private val lastLivePhotoBindings = WeakHashMap<Any, Any>()

    private val originalMediaKeywords = listOf(
        "查看原图", "Full Image", "View Full Image",
        "查看原视频", "Original quality", "View Full Video",
    )


    override fun onEnable() {
        listOf(
            methodSetImageHdImgBtnVisibility,
            methodCheckNeedShowOriginVideoBtn
        ).forEach { method ->
            if (method.isPlaceholder) return@forEach

            method.hookAfter {
                clickVisibleOriginalMediaButtons(thisObject!!)
            }
        }

        if (!methodUpdateMediaGalleryVideoOriginButton.isPlaceholder) {
            methodUpdateMediaGalleryVideoOriginButton.hookAfter {
                if (args[1] as Boolean) return@hookAfter

                val binding = thisObject!!.reflekt().firstField {
                    type { !it.isPrimitive }
                }.get()!!
                val originalVideoButton = binding.reflekt().firstField {
                    type = Button::class
                }.get() as Button
                clickOriginalMediaButton(originalVideoButton)
            }
            methodBindMediaGalleryChatLiveBottomBar.hookAfter {
                val layer = thisObject!!
                val bindContext = args[0]!!
                if (lastLivePhotoBindings[layer] === bindContext) return@hookAfter
                lastLivePhotoBindings[layer] = bindContext

                val binding = layer.reflekt().firstField {
                    type { candidate ->
                        candidate.reflekt().fields {
                            type = MEDIA_DOWNLOAD_TEXT_CLASS
                        }.isNotEmpty()
                    }
                }.get()!!
                val originalImageControl = binding.reflekt().firstField {
                    type = MEDIA_DOWNLOAD_TEXT_CLASS
                }.get() as View

                originalImageControl.post {
                    if (lastLivePhotoBindings[layer] === bindContext &&
                        originalImageControl.isShown &&
                        originalImageControl.isEnabled &&
                        originalImageControl.hasVisibleOriginalMediaLabel()
                    ) {
                        originalImageControl.performClick()
                    }
                }
            }
        }
    }

    private fun clickVisibleOriginalMediaButtons(owner: Any) {
        owner.reflekt().fields {
            type = Button::class
        }.forEach {
            clickOriginalMediaButton(it.get() as Button)
        }
    }

    private fun clickOriginalMediaButton(button: Button) {
        if (button.isVisible && button.text.isOriginalMediaLabel()) {
            button.performClick()
        }
    }

    private fun View.hasVisibleOriginalMediaLabel(): Boolean =
        reflekt().fields {
            type = TextView::class
        }.any {
            val textView = it.get() as TextView
            textView.isVisible && textView.text.isOriginalMediaLabel()
        }

    private fun CharSequence.isOriginalMediaLabel(): Boolean =
        originalMediaKeywords.any { contains(it, ignoreCase = true) }
}
