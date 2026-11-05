package com.xiao.feng.features.api.core

import android.content.Context
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.reflection.BString
import java.lang.reflect.Modifier

object WeServiceApi : ApiFeature(), IResolveDex {

    override val technicalId = "微信服务管理服务"
    override val nameRes = R.string.feature_we_service_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_service_api_description

    private val methodServiceManagerGetService by dexMethod()
    private val classEmojiFeatureService by dexClass()
    private val classStorageFeatureService by dexClass()
    private val classChatroomService by dexClass()
    val classImageInfoStorage by dexClass()
    val methodDownloadImageServiceDownloadImage by dexMethod()
    val classImageFeatureService by dexClass()
    private val methodApiManagerGetApi by dexMethod()
    private val methodMmKernelGetServiceImpl by dexMethod()
    private val methodVideoPathFeatureServiceRestoreMp4Path by dexMethod() // formerly VideoInfoStorage
    val classVideoService by dexClass()
    val classEmojiMgrImpl by dexClass()
    val classEmojiInfoStorage by dexClass()
    private val classEmojiStorageMgr by dexClass()
    val methodSaveEmojiThumb by dexMethod()
    val apiManagerClass: Class<*>
        get() = methodApiManagerGetApi.method.declaringClass

    val emojiFeatureService
        get() = getServiceByClass(classEmojiFeatureService.clazz)

    val emojiStorageMgr
        get() = classEmojiStorageMgr.reflekt().firstMethod {
            modifiers(Modifiers.STATIC)
            returnType = classEmojiStorageMgr.clazz
        }.invokeStatic()!!

    val emojiMgr
        get() = emojiFeatureService.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = classEmojiMgrImpl.clazz
            }.invoke(emojiFeatureService)!!

    val emojiMgrImpl
        get() = emojiFeatureService.reflekt()
            .firstMethod {
                returnType = classEmojiMgrImpl.clazz
            }
            .invoke()!!

    fun processEmojiPath(path: String): String {
        return emojiMgrImpl.reflekt().firstMethod {
            parameters(Context::class, BString)
            returnType = BString
        }.invoke(HostInfo.application, path)!! as String
    }

    fun saveEmojiThumb(path: String): Any {
        return emojiInfoStorage.reflekt().firstMethod {
            parameters(BString)
            returnType = methodSaveEmojiThumb.method.declaringClass
        }.invoke(path)!!
    }

    fun getEmojiMd5FromPath(context: Context, path: String): String {
        return emojiMgrImpl
            .reflekt()
            .firstMethod {
                parameters(Context::class.java, String::class.java)
                returnType = String::class.java
            }
            .invoke(context, path) as String
    }

    val emojiInfoStorage
        get() = emojiStorageMgr.reflekt()
            .firstMethod {
                returnType = classEmojiInfoStorage.clazz
            }
            .invoke()!!

    fun getEmojiInfoByMd5(md5: String): Any {
        return emojiInfoStorage.reflekt()
            .firstMethod {
                parameters(BString)
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
            .invoke(md5)!!
    }

    val storageFeatureService
        get() = getServiceByClass(classStorageFeatureService.clazz)

    val msgInfoStorage
        get() = storageFeatureService.reflekt()
            .firstMethod {
                parameterCount = 0
                returnType = WeMessageApi.classMsgInfoStorage.clazz
            }
            .invoke()!!

    val chatroomService
        get() = getServiceImplByClass(classChatroomService.clazz.interfaces[0])

    val chatroomStorage
        get() =
            chatroomService.reflekt().firstField {
                type = WeConversationApi.methodChatroomStorageGetMemberCount.method.declaringClass
            }.get()!!

    fun getServiceByClass(clazz: Class<*>): Any {
        return methodServiceManagerGetService.method.invoke(null, clazz)!!
    }

    fun getServiceImplByClass(clazz: Class<*>): Any {
        return methodMmKernelGetServiceImpl.method.invoke(null, clazz)!!
    }

    fun getApiByClass(apiManager: Any, clazz: Class<*>): Any {
        return methodApiManagerGetApi.method.invoke(apiManager, clazz.interfaces[0])!!
    }

    val imageInfoStorage
        get() = classImageFeatureService.reflekt()
            .firstMethod {
                modifiers(Modifiers.STATIC)
                returnType = classImageInfoStorage.clazz
            }.invokeStatic()!!

    fun getImageMd5FromMsgInfo(msgInfo: MessageInfo): String {
        return imageInfoStorage.reflekt()
            .firstMethod {
                returnType = String::class
                parameters(WeMessageApi.classMsgInfo.clazz)
            }.invoke(msgInfo.instance)!! as String
    }

    val videoPathFeatureService
        get() = getServiceByClass(
            methodVideoPathFeatureServiceRestoreMp4Path
                .method.declaringClass
                .interfaces[0]
        )

    fun getVideoMp4PathFromMsgInfo(msgInfo: MessageInfo): String {
        return methodVideoPathFeatureServiceRestoreMp4Path.method.invoke(
            videoPathFeatureService, msgInfo.imagePath!!
        ) as String
    }

}
