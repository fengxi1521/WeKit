package com.xiao.feng.features.items.chat

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.WeServiceApi
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.collections.LruCache
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object AutoSpeechToText : SwitchFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "自动语音转文字"
    override val nameRes = R.string.feature_auto_speech_to_text_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_speech_to_text_description

    private val processedMessages = LruCache<Long, Boolean>()

    /**
     * These members are looked up for every voice message that scrolls into view. Resolving them
     * once per class keeps a group chat's voice-message history from re-scanning the hierarchy.
     */
    private val apiManagerFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val transformGetItemCache = ConcurrentHashMap<Class<*>, Method>()
    private val transformInvokeCache = ConcurrentHashMap<Class<*>, Method>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.typeCode != MessageType.VOICE.code) return

        val id = msgInfo.id
        if (processedMessages[id] == true) {
            return
        }

        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)
        val apiManagerField = apiManagerFieldCache.getOrPut(chattingContext.javaClass) {
            chattingContext.reflekt()
                .firstField {
                    type = WeServiceApi.apiManagerClass
                }
                .self.makeAccessible()
        }
        val apiMan = apiManagerField.get(chattingContext)!!
        val api = WeServiceApi.getApiByClass(apiMan, WeMessageApi.classTransformChattingComponent.clazz)
        val transformGetItem = transformGetItemCache.getOrPut(api.javaClass) {
            api.reflekt()
                .firstMethod {
                    parameters(Long::class)
                    returnType { clazz ->
                        clazz.name.startsWith("com.tencent.mm.ui.chatting.viewitems")
                    }
                }
                .self.makeAccessible()
        }
        val chatViewItem = transformGetItem.invoke(api, id)

        if (chatViewItem.toString() != "NoTransform") return

        processedMessages[id] = true

        // Clear the unplayed red dot the same way WeChat does when the voice is listened to,
        // since we consume the message via transform instead of playback.
        runCatching { WeMessageApi.markVoicePlayed(msgInfo) }

        if (WeMessageApi.methodGetIsTransformed.method.invoke(msgInfo.instance) as Boolean) return
        try {
            val transformMessage = transformInvokeCache.getOrPut(api.javaClass) {
                api.reflekt()
                    .firstMethod {
                        parameters(
                            WeMessageApi.classMsgInfo.clazz,
                            Boolean::class,
                            Int::class,
                            Int::class
                        )
                        returnType = Void::class.javaPrimitiveType
                    }
                    .self.makeAccessible()
            }
            transformMessage.invoke(api, msgInfo.instance, false, -1, 0)
        } catch (_: InvocationTargetException) {
            // WeChat throws `java.lang.NullPointerException: getImgPath(...) must not be null`,
            // but that's not what we should care about and doesn't affect functionality
        }
    }
}
