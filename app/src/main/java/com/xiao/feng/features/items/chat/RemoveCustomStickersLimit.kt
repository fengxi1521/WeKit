package com.xiao.feng.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.reflection.int
import java.lang.reflect.Modifier

object RemoveCustomStickersLimit : SwitchFeature(), IResolveDex {

    override val technicalId = "解除单个表情数量上限"
    override val nameRes = R.string.feature_remove_custom_stickers_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_remove_custom_stickers_limit_description

    private val methodGetCustomEmojiMaxSize by dexMethod()

    private val methodComputeEmojiStorageState by dexMethod()

    private val classMmkv by dexClass()

    private val methodGetMmkv by dexMethod()

    private val classCgiBack by dexClass()

    private val methodCreateCgiBack by dexMethod()

    private val methodAddEmojiOnSceneEnd by dexMethod()

    private val methodNetSceneBackupEmojiOperateOnGYNetEnd by dexMethod()

    private val methodChattingUiEmoji by dexMethod()

    private val methodConditionallyShowDialog by dexMethod()

    private fun putCustomFullFalseInMmkv() {
        runCatching {
            val mmkv = methodGetMmkv.method.invoke(null, "emoji_stg", 2, null)
            mmkv.reflekt().invokeMethod("putBoolean", "custom_full", false)
        }
    }

    override fun onEnable() {
        methodGetCustomEmojiMaxSize.hookBefore {
            result = Int.MAX_VALUE
        }

        methodCreateCgiBack.hookBefore {
            if (args[1] as? Int == -434) {
                args[0] = 0
                args[1] = 0
                args[2] = ""
            }
        }

        methodCreateCgiBack.hookAfter {
            val res = result ?: return@hookAfter
            @Suppress("UNCHECKED_CAST")
            (classCgiBack.clazz as Class<Any>).reflekt().fields {
                type = int
            }.forEach {
                if (it.get(res) == -434) {
                    it.set(res, 0)
                }
            }
        }

        methodAddEmojiOnSceneEnd.hookBefore {
            val resp = args[0] ?: return@hookBefore
            @Suppress("UNCHECKED_CAST")
            (classCgiBack.clazz as Class<Any>).reflekt().fields {
                type = int
            }.forEach {
                if (it.get(resp) == -434) {
                    it.set(resp, 0)
                }
            }
        }

        methodNetSceneBackupEmojiOperateOnGYNetEnd.hookBefore {
            if (args[2] as? Int == -434) {
                args[1] = 0
                args[2] = 0
            }
        }

        listOf(
            methodComputeEmojiStorageState,
            methodChattingUiEmoji,
            methodConditionallyShowDialog
        ).forEach {
            it.hookBefore {
                putCustomFullFalseInMmkv()
            }
            it.hookAfter {
                putCustomFullFalseInMmkv()
            }
        }
    }
}
