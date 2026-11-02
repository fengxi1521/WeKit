package com.xiao.feng.features.items.chat

import android.util.SparseBooleanArray
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.xiao.feng.R
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.reflection.cachedField
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object MergeMessagesIntoGroups : SwitchFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "合并消息显示"
    override val nameRes = R.string.feature_merge_messages_into_groups_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_merge_messages_into_groups_description

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        timeVisibilityCache.clear()
    }

    // ── reflection cache ─────────────────────────────────────────────────────

    /** The holder's adapter field and the adapter's `getItem`, resolved once per class. */
    private val adapterFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val adapterGetItemCache = ConcurrentHashMap<Class<*>, Method>()

    private fun adapterOf(param: HookParam): Any {
        val holder = param.thisObject!!
        val field = adapterFieldCache.getOrPut(holder.javaClass) {
            holder.reflekt()
                .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
                .self.makeAccessible()
        }
        return field.get(holder)!!
    }

    private fun itemAt(adapter: Any, position: Int): Any {
        val getItem = adapterGetItemCache.getOrPut(adapter.javaClass) {
            adapter.reflekt().firstMethod { name = "getItem" }.self.makeAccessible()
        }
        return getItem.invoke(adapter, position)!!
    }

    // ── timeTV visibility cache ──────────────────────────────────────────────

    // Keyed by adapter position. Populated as views are bound by the RecyclerView.
    //
    // Used so that when message at position N is being laid out we can ask
    // "does position N+1 start with a timestamp?" without having its View in hand.
    //
    // Default (missing entry) → false, which is the safe fallback: it may
    // occasionally leave a message without its avatar when the next item hasn't
    // been bound yet, but that corrects itself on the next rebind (e.g. a scroll).
    private val timeVisibilityCache = SparseBooleanArray()

    // ── ICreateViewListener ──────────────────────────────────────────────────

    override fun onCreateView(param: HookParam, view: View) {
        val tag = view.tag ?: return

        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (msgInfo.isSend != 0) return
        if (msgInfo.type?.isSystem ?: false || msgInfo.type == MessageType.PAT) return

        val isGroupChat = msgInfo.isInGroupChat
        val currentSender = msgInfo.sender
        val position = param.args[2] as Int

        val adapter = adapterOf(param)

        val currentHasVisibleTime =
            (tag.cachedField("timeTV").get(tag) as? View)?.visibility == View.VISIBLE
        timeVisibilityCache.put(position, currentHasVisibleTime)

        val isFirstInGroup = run {
            val prevSender = senderAt(adapter, position - 1)
            prevSender != currentSender || currentHasVisibleTime
        }

        val nextHasVisibleTime = timeVisibilityCache.get(position + 1, false)
        val isLastInGroup = run {
            val nextSender = senderAt(adapter, position + 1)
            nextSender != currentSender || nextHasVisibleTime
        }

        // Avatar: INVISIBLE (not GONE) so the text column stays aligned
        (tag.cachedField("avatarIV").get(tag) as? View)?.let { avatar ->
            val avatarContainer = avatar.parent as? View ?: avatar
            avatarContainer.visibility =
                if (isLastInGroup) View.VISIBLE else View.INVISIBLE
        }

        // Display Name: only shown in group chats
        if (isGroupChat) {
            (tag.cachedField("userTV").get(tag) as? View)?.visibility =
                if (isFirstInGroup) View.VISIBLE else View.GONE
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun senderAt(adapter: Any, position: Int): String? = runCatching {
        MessageInfo(itemAt(adapter, position)).sender
    }.getOrNull()
}
