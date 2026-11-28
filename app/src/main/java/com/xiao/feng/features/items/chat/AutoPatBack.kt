package com.xiao.feng.features.items.chat

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
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.IWeContact
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
 * `PatMsgExtension.insertPatMsg` 是宿主拍一拍消息的唯一写入点: 第一条拍一拍会插入新消息,
 * 接连的拍一拍则被合并进已有消息。只在数据库插入事件上挂钩会漏掉合并的那些 (表现就是
 * 回拍一次之后第二次没反应), 所以这里直接挂钩写入器本身, 两条分支都能覆盖。
 *
 * 只对「别人拍我」回拍: 自己发出的拍一拍不会再触发, 天然避免死循环。
 */
object AutoPatBack : ClickableFeature(), IResolveDex {

    override val technicalId = "自动回拍"
    override val nameRes = R.string.feature_auto_pat_back_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_pat_back_description

    private const val TAG = "AutoPatBack"
    private const val KEY_TARGETS = "auto_pat_back_targets"

    private const val REPLY_DELAY_MILLIS = 800L

    /** `PatMsgExtension.insertPatMsg(talker, fromUser, pattedUser, ...)`: 拍一拍消息的唯一写入器。 */
    private val methodPatMsgInsert by dexMethod()

    /** 生效会话; 为空表示所有聊天。 */
    var targets: Set<String> by prefOption(KEY_TARGETS, emptySet<String>())

    private var scope = newScope()

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()
        if (methodPatMsgInsert.isPlaceholder) {
            WeLogger.e(TAG, "pat message writer wasn't resolved; auto pat back is skipped")
            return
        }

        methodPatMsgInsert.hookAfter {
            val talker = args[0] as String
            val fromUser = args[1] as String
            val pattedUser = args[2] as String

            // 只有「别人拍我」才回拍; 自己发出的拍一拍不处理。
            if (pattedUser != WeApi.selfWxId || fromUser == WeApi.selfWxId) return@hookAfter
            if (targets.isNotEmpty() && talker !in targets) return@hookAfter

            // 被隐藏联系人拦截时宿主返回 (0, 0), 表示没有真的写入消息, 不回拍。
            val msgId = (result as? android.util.Pair<*, *>)?.first as? Long ?: return@hookAfter
            if (msgId == 0L) return@hookAfter

            scope.launch {
                delay(REPLY_DELAY_MILLIS)
                val sent = runCatching { WeMessageApi.sendPat(talker, fromUser) }.getOrDefault(false)
                WeLogger.i(TAG, "auto pat back: talker=$talker from=$fromUser sent=$sent")
            }
        }
    }

    override fun onDisable() {
        scope.cancel()
    }

    override fun onClick(context: ComponentActivity) = showAutoPatBackDialog(context)

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
