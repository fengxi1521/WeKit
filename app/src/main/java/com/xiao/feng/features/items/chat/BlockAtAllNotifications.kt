package com.xiao.feng.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.data.KvStore
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId

object BlockAtAllNotifications : ClickableFeature() {

    override val technicalId = "屏蔽群聊@所有人"
    override val nameRes = R.string.feature_block_at_all_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_block_at_all_notifications_description

    private var useWhitelist by KvStore.prefOption(KEY_USE_WHITELIST, false)
    private var whitelist by KvStore.prefOption(KEY_WHITELIST, emptySet())
    private var blacklist by KvStore.prefOption(KEY_BLACKLIST, emptySet())

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_block_at_all_notifications_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    if (useWhitelistState) R.string.filter_list_whitelist_selected
                                    else R.string.filter_list_blacklist_selected
                                ),
                                description = stringResource(
                                    if (useWhitelistState) R.string.chat_block_at_all_whitelist_description
                                    else R.string.chat_block_at_all_blacklist_description
                                ),
                                checked = useWhitelistState,
                                onCheckedChange = {
                                    useWhitelistState = it
                                    useWhitelist = it
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    if (useWhitelistState) R.string.filter_list_configure_whitelist
                                    else R.string.filter_list_configure_blacklist
                                ),
                                description = stringResource(R.string.chat_block_at_all_select_groups_hint),
                                onClick = {
                                    val groups = WeDatabaseApi.getGroups()
                                    val currentList = if (useWhitelistState) whitelist else blacklist
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(
                                                if (useWhitelistState) R.string.filter_list_select_whitelist
                                                else R.string.filter_list_select_blacklist
                                            ),
                                            contacts = groups,
                                            initialSelectedWxIds = currentList,
                                            onDismiss = onDismiss,
                                        ) { selectedIds ->
                                            if (useWhitelistState) {
                                                whitelist = selectedIds
                                            } else {
                                                blacklist = selectedIds
                                            }
                                            showToast(
                                                localizedChatQuantity(
                                                    R.plurals.chat_block_at_all_groups_saved,
                                                    selectedIds.size,
                                                    selectedIds.size,
                                                )
                                            )
                                            onDismiss()
                                        }
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    fun shouldSuppress(groupId: String): Boolean {
        if (!groupId.isGroupChatWxId || !KvStore.getBoolOrDef(technicalId, false)) return false
        if (KvStore.getBoolOrDef(KEY_USE_WHITELIST, false)) {
            return groupId !in KvStore.getStringSetOrDef(KEY_WHITELIST, emptySet())
        }
        val blacklist = KvStore.getStringSetOrDef(KEY_BLACKLIST, emptySet())
        // 名单为空时按「屏蔽全部群聊」处理, 开启即生效; 配置了名单就只屏蔽所选群。
        return blacklist.isEmpty() || groupId in blacklist
    }

    private const val KEY_USE_WHITELIST = "block_at_all_notifications_use_whitelist"
    private const val KEY_WHITELIST = "block_at_all_notifications_whitelist"
    private const val KEY_BLACKLIST = "block_at_all_notifications_blacklist"
}

/** Installs the notification hooks in both the main and push processes. */
object BlockAtAllNotificationsRuntime : ApiFeature(), IResolveDex {

    override val technicalId = "群聊@所有人通知拦截服务"
    override val nameRes = R.string.feature_block_at_all_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_block_at_all_notifications_description

    private const val TAG = "BlockAtAllNotifications"

    /** @所有人 在消息/通知文本中的标记。 */
    private val AT_ALL_MARKERS = listOf("@所有人", "@全体成员", "@ all people")

    /**
     * @所有人 消息落库后宿主通常在同一秒内出通知。超出该窗口就不再拦截,
     * 避免把同一群里随后到达的普通消息通知一起吞掉。
     */
    private const val CONSUME_WINDOW_MILLIS = 8_000L
    private const val MAX_TRACKED_GROUPS = 64

    private val stateLock = Any()
    private val lastAtAllAt = HashMap<String, Long>()

    private val methodDealNotify by dexMethod()

    private val methodNotifyForLightPush by dexMethod()

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    override fun onEnable() {
        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            val message = MessageInfo(args[0]!!)
            if (message.isSelfSender || !message.isInGroupChat) return@hookAfter
            if (!message.isAtAllMessage()) return@hookAfter
            markAtAllMessage(message.talker)
        }

        methodDealNotify.hookBefore(100) {
            val groupId = findGroupChatId(args) ?: return@hookBefore
            if (!BlockAtAllNotifications.shouldSuppress(groupId)) return@hookBefore
            if (!hasRecentAtAll(groupId) && !hasAtAllText(args)) return@hookBefore
            clearAtAll(groupId)
            WeLogger.i(TAG, "suppressing @all notification from $groupId")
            result = null
        }

        methodNotifyForLightPush.hookBefore(100) {
            val groupId = findGroupChatId(args) ?: return@hookBefore
            if (!BlockAtAllNotifications.shouldSuppress(groupId)) return@hookBefore
            val msgSource = args[5] as Map<*, *>?
            if (!hasRecentAtAll(groupId) && !msgSource.containsAtAllMention()) return@hookBefore
            clearAtAll(groupId)
            WeLogger.i(TAG, "suppressing LightPush @all notification from $groupId")
            result = null
        }
    }

    override fun onDisable() {
        synchronized(stateLock) { lastAtAllAt.clear() }
    }

    /** 优先用宿主的 msgsource 判定, 拿不到 lvBuffer 时退回文本标记。 */
    private fun MessageInfo.isAtAllMessage(): Boolean =
        isNotifyAll || AT_ALL_MARKERS.any { actualContent.contains(it) }

    /** 通知参数里群号不一定排在固定位置, 统一在所有字符串参数里找 @chatroom。 */
    private fun findGroupChatId(args: Array<Any?>): String? =
        args.filterIsInstance<String>().firstOrNull { it.isGroupChatWxId }

    private fun hasAtAllText(args: Array<Any?>): Boolean =
        args.any { arg -> arg is String && AT_ALL_MARKERS.any { arg.contains(it) } }

    private fun markAtAllMessage(groupId: String) {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            if (lastAtAllAt.size >= MAX_TRACKED_GROUPS) {
                lastAtAllAt.entries.removeAll { now - it.value > CONSUME_WINDOW_MILLIS }
            }
            lastAtAllAt[groupId] = now
        }
    }

    private fun hasRecentAtAll(groupId: String): Boolean {
        val now = System.currentTimeMillis()
        return synchronized(stateLock) {
            val capturedAt = lastAtAllAt[groupId] ?: return@synchronized false
            if (now - capturedAt > CONSUME_WINDOW_MILLIS) {
                lastAtAllAt.remove(groupId)
                false
            } else {
                true
            }
        }
    }

    private fun clearAtAll(groupId: String) {
        synchronized(stateLock) { lastAtAllAt.remove(groupId) }
    }

    /** msgsource 可能保持 XML 字符串, 也可能已被宿主解析成嵌套 Map。 */
    private fun Any?.containsAtAllMention(): Boolean = when (val value = this) {
        is String -> value.contains("notify@all") || value.contains("announcement@all")
        is Map<*, *> -> value.values.any { it.containsAtAllMention() }
        is Iterable<*> -> value.any { it.containsAtAllMention() }
        else -> false
    }
}
