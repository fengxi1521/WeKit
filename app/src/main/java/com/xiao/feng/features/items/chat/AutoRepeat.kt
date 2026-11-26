package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.strings.isGroupChatWxId
import com.xiao.feng.utils.strings.stripWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTO_REPEAT_DEFAULT_THRESHOLD = 2
private const val AUTO_REPEAT_MIN_THRESHOLD = 2
private const val AUTO_REPEAT_MAX_THRESHOLD = 20

/**
 * 会话里连续出现相同的文本消息达到设定条数后, 自动发送同一句话。
 *
 * 计数按会话进行, 且不区分发送者 (群聊里不同人连续发同一句也算复读)。自己发出的消息
 * (`isSend != 0`) 完全不参与计数, 所以自动发出的复读不会反过来触发自己。
 */
object AutoRepeat : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自动复读"
    override val nameRes = R.string.feature_auto_repeat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_repeat_description

    private const val TAG = "AutoRepeat"
    private const val KEY_TARGETS = "auto_repeat_targets"
    private const val KEY_THRESHOLD = "auto_repeat_threshold"

    private const val SEND_DELAY_MILLIS = 500L
    private const val SEND_COOLDOWN_MILLIS = 3_000L
    private const val MAX_SEEN_KEYS = 256

    /** 生效会话; 为空表示所有聊天。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    /** 连续相同消息达到该条数后自动复读。 */
    var repeatThreshold: Int by prefOption(KEY_THRESHOLD, AUTO_REPEAT_DEFAULT_THRESHOLD)

    private class ConversationState(
        var lastText: String = "",
        var count: Int = 0,
        var lastSentAt: Long = 0L,
    )

    private val lock = Any()
    private val states = HashMap<String, ConversationState>()
    private val seenKeys = LinkedHashSet<String>()

    private var scope = newScope()

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        synchronized(lock) {
            states.clear()
            seenKeys.clear()
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return
        if ((values.getAsInteger("type") ?: return) != MessageType.TEXT.code) return

        val talker = values.getAsString("talker") ?: return
        if (targets.isNotEmpty() && talker !in targets) return

        val rawContent = values.getAsString("content") ?: return
        val text = (if (talker.isGroupChatWxId) rawContent.stripWxId() else rawContent).trim()
        if (text.isEmpty()) return

        val threshold = repeatThreshold.coerceIn(AUTO_REPEAT_MIN_THRESHOLD, AUTO_REPEAT_MAX_THRESHOLD)
        val now = System.currentTimeMillis()
        val shouldRepeat = synchronized(lock) {
            // 宿主可能重复插入同一行, 先按消息标识去重, 避免把一条消息算成两条。
            val dedupKey = "$talker|${values.get("createTime")}|${values.get("msgSvrId")}"
            if (!seenKeys.add(dedupKey)) return@synchronized false
            if (seenKeys.size > MAX_SEEN_KEYS) {
                seenKeys.iterator().run {
                    next()
                    remove()
                }
            }

            val state = states.getOrPut(talker) { ConversationState() }
            if (text == state.lastText) {
                state.count++
            } else {
                state.lastText = text
                state.count = 1
            }

            if (state.count >= threshold && now - state.lastSentAt >= SEND_COOLDOWN_MILLIS) {
                state.count = 0
                state.lastSentAt = now
                true
            } else {
                false
            }
        }
        if (!shouldRepeat) return

        scope.launch {
            delay(SEND_DELAY_MILLIS)
            val sent = runCatching { WeMessageApi.sendText(talker, text) }.getOrDefault(false)
            WeLogger.i(TAG, "auto repeat: talker=$talker threshold=$threshold sent=$sent")
        }
    }

    override fun onClick(context: ComponentActivity) = showAutoRepeatDialog(context)

    /** 「进群自动启用」入口: 把新群加入生效集合。 */
    fun addToGroup(groupId: String) {
        if (targets.isEmpty()) return  // 全部模式, 无需追加
        if (groupId in targets) return
        targets = targets + groupId
    }

    /** 「退群清理」入口: 从生效集合移除。 */
    fun removeFromGroup(groupId: String) {
        if (groupId !in targets) return
        targets = targets - groupId
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

private fun showAutoRepeatDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var targets by remember { mutableStateOf(AutoRepeat.targets) }
        var threshold by remember {
            mutableIntStateOf(
                AutoRepeat.repeatThreshold.coerceIn(AUTO_REPEAT_MIN_THRESHOLD, AUTO_REPEAT_MAX_THRESHOLD)
            )
        }
        var contacts by remember { mutableStateOf<List<IWeContact>>(emptyList()) }
        LaunchedEffect(Unit) {
            contacts = withContext(Dispatchers.IO) {
                if (!WeDatabaseApi.isReady) return@withContext emptyList<IWeContact>()
                runCatching {
                    buildList {
                        addAll(WeDatabaseApi.getFriends())
                        addAll(WeDatabaseApi.getGroups())
                    }
                }.getOrDefault(emptyList())
            }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.feature_auto_repeat_name)) },
            text = {
                DefaultColumn {
                    Text(
                        stringResource(R.string.feature_auto_repeat_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ListItem(
                        modifier = Modifier.clickable {
                            showComposeDialog(context) {
                                ContactsSelector(
                                    title = stringResource(R.string.keyword_auto_reply_targets_select),
                                    contacts = contacts,
                                    initialSelectedWxIds = targets,
                                    onDismiss = this.onDismiss,
                                    onConfirm = { selected ->
                                        targets = selected
                                        AutoRepeat.targets = selected
                                        this.onDismiss()
                                    },
                                )
                            }
                        },
                        content = { Text(stringResource(R.string.keyword_auto_reply_targets_title)) },
                        supportingContent = {
                            Text(
                                if (targets.isEmpty()) {
                                    stringResource(R.string.keyword_auto_reply_targets_empty)
                                } else {
                                    stringResource(R.string.keyword_auto_reply_targets_count, targets.size)
                                }
                            )
                        },
                    )

                    BaseItemContainer {
                        IntNumberPickerWidget(
                            title = stringResource(R.string.auto_repeat_threshold_title),
                            description = stringResource(R.string.auto_repeat_threshold_description),
                            value = threshold,
                            startInt = AUTO_REPEAT_MIN_THRESHOLD,
                            endInt = AUTO_REPEAT_MAX_THRESHOLD,
                            stepSize = 1,
                            valueSuffix = stringResource(R.string.auto_repeat_threshold_suffix),
                            onValueChange = { value ->
                                threshold = value
                                AutoRepeat.repeatThreshold = value
                            },
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_close))
                }
            },
        )
    }
}
