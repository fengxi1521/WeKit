package com.xiao.feng.features.items.chat

import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.WeContact
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getSystemService
import com.xiao.feng.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * 分享进化: 把最近聊天最频繁的好友发布成微信的动态快捷方式。
 *
 * 发布出的快捷方式同时用于两处:
 * - Android 系统分享面板的 "直达分享" (Direct Share): 直接把内容分享给该好友;
 * - 长按微信桌面图标的快捷方式列表: 点一下进入分享给该好友。
 *
 * 直达分享要求快捷方式的 category 与宿主 `shortcuts.xml` 里 `<share-target>` 声明的
 * category 一致, 所以运行时读取宿主自己的共享目标声明, 而不是写死。
 */
object ExternalSharingEvolved : ClickableFeature() {

    override val technicalId = "分享进化"
    override val nameRes = R.string.feature_external_sharing_evolved_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_external_sharing_evolved_description

    private const val TAG = "ExternalSharingEvolved"

    private const val SHORTCUT_ID_PREFIX = "xfeng_share_"
    private const val DESIRED_TARGET_COUNT = 4
    private const val AVATAR_ICON_SIZE = 192
    private const val REFRESH_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    private const val FALLBACK_SHARE_ACTIVITY = "com.tencent.mm.ui.tools.ShareImgUI"
    private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    private const val SHORTCUTS_META_DATA = "android.app.shortcuts"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private var scope: CoroutineScope? = null

    override fun onEnable() {
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        newScope.launch {
            delay(1.seconds) // 等宿主把数据库准备好
            while (isActive) {
                runCatching { publishShortcuts() }
                    .onFailure { WeLogger.w(TAG, "failed to publish share shortcuts", it) }
                delay(REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    override fun onDisable() {
        scope?.cancel()
        scope = null
        runCatching { removeOwnShortcuts() }
            .onFailure { WeLogger.w(TAG, "failed to remove share shortcuts", it) }
    }

    override fun onClick(context: ComponentActivity) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val count = runCatching { publishShortcuts() }.getOrDefault(0)
            showToastSuspend(
                context,
                if (count > 0) {
                    localizedChatString(R.string.external_sharing_evolved_refreshed, count)
                } else {
                    localizedChatString(R.string.external_sharing_evolved_unavailable)
                },
            )
        }
    }

    /** 重新发布常用好友快捷方式, 返回实际发布的数量。 */
    private fun publishShortcuts(): Int {
        val ctx = HostInfo.application
        val shortcutManager = ctx.getSystemService<ShortcutManager>()

        val dynamicShortcuts = shortcutManager.dynamicShortcuts
        val ownIds = dynamicShortcuts.map { it.id }.filter { it.startsWith(SHORTCUT_ID_PREFIX) }
        val othersCount = dynamicShortcuts.size - ownIds.size
        val capacity = (shortcutManager.maxShortcutCountPerActivity - othersCount).coerceAtLeast(0)
        if (capacity == 0) {
            WeLogger.w(TAG, "no shortcut slot left, host already uses $othersCount")
            return 0
        }

        val friends = WeDatabaseApi.getFriendsOrderedByMessageCount(DESIRED_TARGET_COUNT)
            .keys
            .take(capacity)
        if (friends.isEmpty()) {
            WeLogger.w(TAG, "no friend found to publish")
            if (ownIds.isNotEmpty()) shortcutManager.removeDynamicShortcuts(ownIds)
            return 0
        }

        val shareTarget = resolveHostShareTarget()
        val fallbackIcon = Icon.createWithResource(ctx, HostInfo.appInfo.icon)
        val shortcuts = friends.mapIndexed { index, friend ->
            buildShortcut(ctx, friend, index, shareTarget, fallbackIcon)
        }

        if (ownIds.isNotEmpty()) shortcutManager.removeDynamicShortcuts(ownIds)
        shortcutManager.addDynamicShortcuts(shortcuts)
        WeLogger.d(
            TAG,
            "published ${shortcuts.size} share shortcuts " +
                    "(target=${shareTarget?.targetClass ?: FALLBACK_SHARE_ACTIVITY}, " +
                    "categories=${shareTarget?.categories})",
        )
        return shortcuts.size
    }

    private fun buildShortcut(
        context: Context,
        friend: WeContact,
        rank: Int,
        shareTarget: HostShareTarget?,
        fallbackIcon: Icon,
    ): ShortcutInfo {
        val displayName = friend.remarkName.ifBlank { friend.nickname }
        val targetClass = shareTarget?.targetClass
            ?.let { if (it.startsWith(".")) HostInfo.packageName + it else it }
            ?: FALLBACK_SHARE_ACTIVITY

        val intent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(HostInfo.packageName, targetClass)
            type = shareTarget?.mimeTypes?.firstOrNull() ?: "*/*"
            // 微信内部用于跳过选人界面、直接定位到该好友的字段。
            putExtra("Select_Conv_User", friend.wxId)
            putExtra("I_am_from_share_msg", true)
            putExtra("Intent_Direct_Share", true)
        }

        val categories = shareTarget?.categories.orEmpty()

        return ShortcutInfo.Builder(context, SHORTCUT_ID_PREFIX + friend.wxId)
            .setShortLabel(displayName)
            .setLongLabel(displayName)
            .setIcon(loadAvatarIcon(friend) ?: fallbackIcon)
            .setRank(rank)
            .apply {
                if (categories.isNotEmpty()) setCategories(categories)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setPerson(
                        Person.Builder()
                            .setName(displayName)
                            .setKey(friend.wxId)
                            .setImportant(true)
                            .build()
                    )
                }
                // 长驻快捷方式才能作为系统分享面板的直达分享目标 (API 30+)。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setLongLived(true)
                }
            }
            .setIntent(intent)
            .build()
    }

    /** 头像优先 (分享面板里更容易辨认), 加载失败时使用微信图标。 */
    private fun loadAvatarIcon(friend: WeContact): Icon? {
        val source = friend.avatarUrl.takeIf { it.isNotBlank() } ?: return null
        val bitmap = runCatching {
            if (source.startsWith("http", ignoreCase = true)) {
                httpClient.newCall(Request.Builder().url(source).build()).execute().use { response ->
                    if (!response.isSuccessful) return null
                    BitmapFactory.decodeStream(response.body.byteStream())
                }
            } else {
                BitmapFactory.decodeFile(source)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to load avatar for ${friend.wxId}", it)
        }.getOrNull() ?: return null

        val scaled = if (bitmap.width == AVATAR_ICON_SIZE && bitmap.height == AVATAR_ICON_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, AVATAR_ICON_SIZE, AVATAR_ICON_SIZE, true)
        }
        return Icon.createWithBitmap(scaled)
    }

    private fun removeOwnShortcuts() {
        val shortcutManager = HostInfo.application.getSystemService<ShortcutManager>()
        val ownIds = shortcutManager.dynamicShortcuts
            .map { it.id }
            .filter { it.startsWith(SHORTCUT_ID_PREFIX) }
        if (ownIds.isNotEmpty()) shortcutManager.removeDynamicShortcuts(ownIds)
    }

    /**
     * 读取宿主 `shortcuts.xml` 中声明的 `<share-target>`, 取一个支持分享给好友的目标。
     * 优先取通配 MIME 类型的目标, 这样文字/图片/文件都能直达分享。
     */
    private fun resolveHostShareTarget(): HostShareTarget? {
        val targets = runCatching { readHostShareTargets() }
            .onFailure { WeLogger.w(TAG, "failed to parse host shortcuts.xml", it) }
            .getOrDefault(emptyList())
            .filter { it.categories.isNotEmpty() }
        if (targets.isEmpty()) return null
        return targets.firstOrNull { "*/*" in it.mimeTypes }
            ?: targets.firstOrNull { it.targetClass.endsWith("ShareImgUI") }
            ?: targets.first()
    }

    private fun readHostShareTargets(): List<HostShareTarget> {
        val resources = HostInfo.application.resources
        val resId = HostInfo.appInfo.metaData?.getInt(SHORTCUTS_META_DATA) ?: 0
        if (resId == 0) return emptyList()

        val parser = resources.getXml(resId)
        val targets = mutableListOf<HostShareTarget>()
        var current: HostShareTargetBuilder? = null
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "share-target" -> current = HostShareTargetBuilder(
                            targetClass = parser.getAttributeValue(ANDROID_NAMESPACE, "targetClass"),
                        )
                        "data" -> current?.mimeTypes?.add(
                            parser.getAttributeValue(ANDROID_NAMESPACE, "mimeType") ?: "*/*"
                        )
                        "category" -> parser.getAttributeValue(ANDROID_NAMESPACE, "name")
                            ?.let { current?.categories?.add(it) }
                    }

                    XmlPullParser.END_TAG -> if (parser.name == "share-target") {
                        current?.build()?.let(targets::add)
                        current = null
                    }
                }
            }
        } finally {
            runCatching { parser.close() }
        }
        return targets
    }

    private data class HostShareTarget(
        val targetClass: String,
        val mimeTypes: Set<String>,
        val categories: Set<String>,
    )

    private class HostShareTargetBuilder(val targetClass: String?) {
        val mimeTypes = mutableSetOf<String>()
        val categories = mutableSetOf<String>()

        fun build(): HostShareTarget? {
            val className = targetClass?.takeIf { it.isNotBlank() } ?: return null
            return HostShareTarget(className, mimeTypes, categories)
        }
    }
}
