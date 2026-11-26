package com.xiao.feng.features.items.chat

import android.content.ContentValues
import android.content.Context
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
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
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
 * 跟发复读: 指定某个人, 他在哪个聊天里发什么, 就在同一个聊天里跟着发什么。
 *
 * 与 [AutoRepeat] 的区别: AutoRepeat 是「同一句话被连续刷屏达到阈值后复读」, 这里只盯着指定的人,
 * 不看条数; 内容类型走 [RepeatMessages] 的完整复读能力 (文字/引用/图片/语音/视频/表情/appmsg)。
 *
 * 自己发出的消息 (`isSend != 0`) 不参与, 所以自动跟发不会反过来触发自己。
 */
object FollowRepeat : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "跟发复读"
    override val nameRes = R.string.feature_follow_repeat_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_follow_repeat_description

    private const val TAG = "FollowRepeat"
    private const val KEY_FOLLOWERS = "follow_repeat_followers"

    private const val SEND_DELAY_MILLIS = 350L
    private const val MEDIA_DELAY_MILLIS = 1_200L
    private const val MAX_SEEN_KEYS = 256

    /** 要跟发的对象 wxId 集合; 为空则什么都不做。 */
    var followers: Set<String> by prefOption(KEY_FOLLOWERS, emptySet<String>())

    private val lock = Any()
    private val seenKeys = LinkedHashSet<String>()

    private var scope = newScope()

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
        synchronized(lock) { seenKeys.clear() }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if ((values.getAsInteger("isSend") ?: 0) != 0) return

        val targets = followers
        if (targets.isEmpty()) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrElse {
            WeLogger.w(TAG, "failed to build MessageInfo from insert", it)
            return
        }
        val sender = runCatching { msgInfo.sender }.getOrElse {
            WeLogger.w(TAG, "failed to resolve sender: talker=${msgInfo.talker}", it)
            return
        }
        if (sender !in targets) return
        if (!RepeatMessages.isSupported(msgInfo)) return

        // 去重键直接用原始列, 避免访问未填充的 msgId 字段。
        val dedupKey = "${msgInfo.talker}|${values.getAsLong("createTime")}|${values.getAsLong("msgSvrId")}"
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
                "follow repeat: talker=${msgInfo.talker} sender=$sender type=${msgInfo.typeCode} sent=$sent",
            )
        }
    }

    override fun onClick(context: ComponentActivity) = showFollowRepeatDialog(context)

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())
}

fun showFollowRepeatDialog(context: Context) {
    showComposeDialog(context) {
        var followers by remember { mutableStateOf(FollowRepeat.followers) }
        var contacts by remember { mutableStateOf<List<IWeContact>>(emptyList()) }
        LaunchedEffect(Unit) {
            contacts = withContext(Dispatchers.IO) {
                if (!WeDatabaseApi.isReady) return@withContext emptyList<IWeContact>()
                runCatching {
                    buildList<IWeContact> { addAll(WeDatabaseApi.getContacts()) }
                }.getOrDefault(emptyList())
            }
        }

        AlertDialogContent(
            modifier = Modifier.fillMaxWidth(),
            title = { Text(stringResource(R.string.feature_follow_repeat_name)) },
            text = {
                DefaultColumn {
                    Text(
                        stringResource(R.string.feature_follow_repeat_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ListItem(
                        modifier = Modifier.clickable {
                            showComposeDialog(context) {
                                ContactsSelector(
                                    title = stringResource(R.string.follow_repeat_targets_select),
                                    contacts = contacts,
                                    initialSelectedWxIds = followers,
                                    onDismiss = this.onDismiss,
                                    onConfirm = { selected ->
                                        followers = selected
                                        FollowRepeat.followers = selected
                                        this.onDismiss()
                                    },
                                )
                            }
                        },
                        content = { Text(stringResource(R.string.follow_repeat_targets_title)) },
                        supportingContent = {
                            Text(
                                if (followers.isEmpty()) {
                                    stringResource(R.string.follow_repeat_targets_empty)
                                } else {
                                    stringResource(R.string.follow_repeat_targets_count, followers.size)
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
