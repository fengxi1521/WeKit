package com.xiao.feng.features.items.chat

import android.content.ContentValues
import android.content.Context
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.ui.WeChatInputBarMenuApi
import com.xiao.feng.features.api.ui.WeCurrentConversationApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.features.items.chat_input_bar_menu.localizedChatInputString
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 跟发复读: 和「拍一拍群友」一样的交互 —— 在当前群聊里挑群友, 他发什么就跟发什么。
 *
 * 跟发关系按群保存 (记录格式 `群ID|群友wxid`), 所以只在配置过的群里对指定群友生效;
 * 换到别的群或私聊都不会触发。内容类型走 [RepeatMessages] (文字/引用/图片/语音/视频/表情/appmsg)。
 *
 * 自己发出的消息 (`isSend != 0`) 不参与, 自动跟发不会反过来触发自己。
 */
object FollowRepeat : SwitchFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "跟发复读"
    override val nameRes = R.string.feature_follow_repeat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_follow_repeat_description

    /** 默认开启: 只是让群聊的「+」菜单里能看到入口, 没选群友时不会触发任何行为。 */
    override val defaultEnabled = true

    private const val TAG = "FollowRepeat"
    private const val KEY_ENTRIES = "follow_repeat_entries"

    private const val SEND_DELAY_MILLIS = 350L
    private const val MEDIA_DELAY_MILLIS = 1_200L
    private const val MAX_SEEN_KEYS = 256

    /** 每项是 `群ID|群友wxid`, 表示该群里要跟发的群友。 */
    private var entries: Set<String> by prefOption(KEY_ENTRIES, emptySet<String>())

    private val lock = Any()
    private val seenKeys = LinkedHashSet<String>()

    private var scope = newScope()

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "follow_repeat",
                icon = MaterialSymbols.Outlined.Content_copy,
                label = localizedChatInputString(R.string.feature_follow_repeat_name),
                isSupported = { _, _ -> WeCurrentConversationApi.value.isGroupChatWxId },
                onClick = { context, _ -> showMemberSelector(context) },
                // 长按清空本群的跟发设置。
                onLongClick = { context, _ -> clearCurrentGroup(context) },
            ),
        )
    }

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeChatInputBarMenuApi.addProvider(provider)
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        synchronized(lock) { seenKeys.clear() }
    }

    fun followersOf(groupId: String): Set<String> {
        val prefix = "$groupId|"
        return entries.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    private fun setFollowers(groupId: String, members: Set<String>) {
        val prefix = "$groupId|"
        entries = entries.filterNot { it.startsWith(prefix) }.toSet() +
                members.map { "$prefix$it" }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.isGroupChatWxId) return

        val snapshot = entries
        if (snapshot.isEmpty()) return
        val prefix = "$talker|"
        if (snapshot.none { it.startsWith(prefix) }) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrElse {
            WeLogger.w(TAG, "failed to build MessageInfo from insert: talker=$talker", it)
            return
        }
        val sender = runCatching { msgInfo.sender }.getOrElse {
            WeLogger.w(TAG, "failed to resolve sender: talker=$talker", it)
            return
        }
        if ("$prefix$sender" !in snapshot) return
        if (!RepeatMessages.isSupported(msgInfo)) return

        // 收到的新消息在插入阶段可能还没有 createTime/msgSvrId; 只按这两个字段去重会把后续
        // 消息全当成同一行丢弃, 导致跟发复读只对第一条消息生效。这里把发送者和内容也算进去。
        val dedupKey =
            "$talker|$sender|${msgInfo.typeCode}|${values.getAsString("content")}|" +
                "${values.getAsLong("createTime")}|${values.getAsLong("msgSvrId")}"
        synchronized(lock) {
            if (!seenKeys.add(dedupKey)) return
            if (seenKeys.size > MAX_SEEN_KEYS) {
                seenKeys.iterator().run {
                    next()
                    remove()
                }
            }
        }

        // 媒体消息可能还在下载/落盘, 稍等再取文件路径, 否则语音时长或图片 md5 会取不到。
        val delayMillis = when (msgInfo.type) {
            MessageType.IMAGE, MessageType.VOICE,
            MessageType.VIDEO, MessageType.MICRO_VIDEO -> MEDIA_DELAY_MILLIS
            else -> SEND_DELAY_MILLIS
        }
        scope.launch {
            delay(delayMillis)
            val sent = runCatching { RepeatMessages.repeatMessage(msgInfo) }.getOrDefault(false)
            WeLogger.i(
                TAG,
                "follow repeat: talker=$talker sender=$sender type=${msgInfo.typeCode} sent=$sent",
            )
        }
    }

    private fun showMemberSelector(context: Context) {
        val talker = WeCurrentConversationApi.value
        if (!talker.isGroupChatWxId) {
            showToast(context, localizedChatInputString(R.string.follow_repeat_group_only))
            return
        }
        val members = WeDatabaseApi.getGroupMembers(talker).filter { it.wxId != WeApi.selfWxId }
        if (members.isEmpty()) {
            showToast(context, localizedChatInputString(R.string.follow_repeat_members_empty))
            return
        }

        showComposeDialog(context) {
            ContactsSelector(
                title = localizedChatInputString(R.string.follow_repeat_member_select),
                contacts = members,
                initialSelectedWxIds = followersOf(talker),
                onDismiss = onDismiss,
                onConfirm = { selected ->
                    setFollowers(talker, selected)
                    onDismiss()
                    showToast(
                        context,
                        localizedChatInputString(R.string.follow_repeat_saved, selected.size),
                    )
                },
            )
        }
    }

    private fun clearCurrentGroup(context: Context) {
        val talker = WeCurrentConversationApi.value
        if (!talker.isGroupChatWxId) return
        if (followersOf(talker).isEmpty()) {
            showToast(context, localizedChatInputString(R.string.follow_repeat_none_in_group))
            return
        }
        setFollowers(talker, emptySet())
        showToast(context, localizedChatInputString(R.string.follow_repeat_cleared))
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
