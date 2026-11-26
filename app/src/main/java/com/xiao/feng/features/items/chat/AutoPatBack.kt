package com.xiao.feng.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.ContactsSelector
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.utils.ListItem
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 别人拍我一拍时, 自动在同一个会话里拍回去。
 *
 * 触发点是宿主写入消息表 (`WeDatabaseListenerApi.IInsertListener`), 因此不依赖 Dex 目标。
 * 只对「别人拍我」回拍: 自己发出的拍一拍 (`fromUser == self`) 不会再触发, 天然避免死循环。
 */
object AutoPatBack : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自动回拍"
    override val nameRes = R.string.feature_auto_pat_back_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_pat_back_description

    private const val TAG = "AutoPatBack"
    private const val KEY_TARGETS = "auto_pat_back_targets"

    private const val REPLY_DELAY_MILLIS = 800L
    private const val REPLY_COOLDOWN_MILLIS = 3_000L
    private const val MAX_DEDUP_KEYS = 128

    /** 生效会话; 为空表示所有聊天。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    private val lock = Any()
    private val dedupKeys = LinkedHashSet<String>()
    private val lastReplyAt = HashMap<String, Long>()

    private var scope = newScope()

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        synchronized(lock) {
            dedupKeys.clear()
            lastReplyAt.clear()
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if ((values.getAsInteger("type") ?: return) != MessageType.PAT.code) return

        val talker = values.getAsString("talker") ?: return
        if (targets.isNotEmpty() && talker !in targets) return

        val content = values.getAsString("content") ?: return
        val (pattedUser, fromUser) = runCatching {
            val pat = MessageInfo.PatMessage(content)
            pat.pattedUser to pat.fromUser
        }.getOrNull() ?: return

        // 只有「别人拍我」才回拍; 自己发出的拍一拍不处理。
        if (pattedUser != WeApi.selfWxId || fromUser == WeApi.selfWxId) return

        val replyKey = "$talker|$fromUser"
        val dedupKey = "$replyKey|${values.get("createTime")}|${values.get("msgSvrId")}"
        if (!markReplyAllowed(dedupKey, replyKey)) return

        scope.launch {
            delay(REPLY_DELAY_MILLIS)
            val sent = runCatching { WeMessageApi.sendPat(talker, fromUser) }.getOrDefault(false)
            WeLogger.i(TAG, "auto pat back: talker=$talker from=$fromUser sent=$sent")
        }
    }

    /** 宿主可能重复插入同一行, 这里去重; 同时限制同一人短时间内被连续回拍。 */
    private fun markReplyAllowed(dedupKey: String, replyKey: String): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        val coolingDown = lastReplyAt[replyKey]?.let { now - it < REPLY_COOLDOWN_MILLIS } ?: false
        if (dedupKeys.contains(dedupKey) || coolingDown) return@synchronized false

        if (dedupKeys.size >= MAX_DEDUP_KEYS) {
            dedupKeys.iterator().run {
                next()
                remove()
            }
        }
        dedupKeys += dedupKey
        lastReplyAt[replyKey] = now
        true
    }

    override fun onClick(context: ComponentActivity) = showAutoPatBackDialog(context)

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

private fun showAutoPatBackDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var targets by remember { mutableStateOf(AutoPatBack.targets) }
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
            title = { Text(stringResource(R.string.feature_auto_pat_back_name)) },
            text = {
                DefaultColumn {
                    Text(
                        stringResource(R.string.feature_auto_pat_back_description),
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
                                        AutoPatBack.targets = selected
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
