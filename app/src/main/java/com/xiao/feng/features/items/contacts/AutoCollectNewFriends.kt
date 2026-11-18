package com.xiao.feng.features.items.contacts

import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 加好友自动归拢: 检测到新好友后, 自动把他加入预先选好的标签 / 归拢 / 分组。
 *
 * 以 rcontact 的写入作为「成为好友」的结构信号, 并在首次启用时先把现有好友全部记为已知,
 * 避免登录同步等场景把老好友重复处理一遍。
 */
object AutoCollectNewFriends : ClickableFeature(), WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AutoCollectNewFriends"
    private const val KEY_TARGET_KIND = "auto_collect_new_friends_target_kind"
    private const val KEY_TARGET_ID = "auto_collect_new_friends_target_id"
    private const val KEY_TARGET_NAME = "auto_collect_new_friends_target_name"
    private const val KEY_INTERVAL = "auto_collect_new_friends_interval_seconds"
    private const val KEY_KNOWN_FRIENDS = "auto_collect_new_friends_known"
    private const val KEY_BASELINE_DONE = "auto_collect_new_friends_baseline_done"

    override val technicalId = "加好友自动归拢"
    override val nameRes = R.string.feature_auto_collect_new_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_collect_new_friends_description

    private var targetKindValue by KvStore.prefOption(KEY_TARGET_KIND, "")
    private var targetId by KvStore.prefOption(KEY_TARGET_ID, "")
    private var targetName by KvStore.prefOption(KEY_TARGET_NAME, "")
    private var intervalSeconds by KvStore.prefOption(KEY_INTERVAL, "1")
    private var knownFriends by KvStore.prefOption(KEY_KNOWN_FRIENDS, emptySet<String>())
    private var baselineDone by KvStore.prefOption(KEY_BASELINE_DONE, false)

    private val baselineLock = Mutex()
    private val applyLock = Mutex()
    private var scope: CoroutineScope? = null

    override fun onEnable() {
        val currentScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = currentScope
        WeDatabaseListenerApi.addListener(this)
        // 先把现有好友记为已知, 否则登录同步的 rcontact 写入会被当成新好友。
        currentScope.launch { ensureBaseline() }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope?.cancel()
        scope = null
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "rcontact") return
        val username = values.getAsString("username") ?: return
        if (!looksLikeFriend(username, values)) return
        val currentScope = scope ?: return
        currentScope.launch { handleNewFriend(username) }
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int,
    ) {
        if (table != "rcontact") return
        val username = values.getAsString("username")
            ?: whereArgs?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: return
        val currentScope = scope ?: return
        // 更新在写入前回调, 等落库后再确认这位联系人现在确实是好友。
        currentScope.launch {
            delay(UPDATE_SETTLE_MS)
            if (WeDatabaseApi.getFriend(username) == null) return@launch
            handleNewFriend(username)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var targets by remember { mutableStateOf<List<ContactCollector.Target>?>(null) }
            var intervalInput by remember { mutableStateOf(intervalSeconds) }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    targets = ContactCollector.loadTargets(
                        context = context,
                        includeLabels = true,
                        includeCollections = true,
                    )
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_collect_new_friends_name)) },
                text = {
                    val loaded = targets
                    DefaultColumn {
                        Text(currentTargetSummary(context))
                        if (loaded == null) {
                            LinearWavyProgressIndicator()
                        } else {
                            ContactCollectionTargetList(
                                targets = loaded,
                                allowNewLabel = true,
                                intervalInput = intervalInput,
                                onIntervalChange = { intervalInput = it },
                                onPick = { target ->
                                    targetKindValue = target.kind.name
                                    targetId = target.id
                                    targetName = target.name
                                    intervalSeconds = intervalInput
                                    showToast(
                                        context,
                                        context.localizedContactsString(
                                            R.string.contact_collection_saved,
                                            target.name,
                                        ),
                                    )
                                    onDismiss()
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    private suspend fun handleNewFriend(wxId: String) {
        val isNew = baselineLock.withLock {
            // 数据库未就绪时不建立基线, 此时的新写入一律跳过, 避免把登录同步的老好友当成新好友。
            if (!baselineDone && !mergeCurrentFriends()) return@withLock false
            if (wxId in knownFriends) {
                false
            } else {
                knownFriends = knownFriends + wxId
                true
            }
        }
        if (!isNew) return

        // 串行执行, 保证多个新好友同时到达时也按配置的间隔逐个处理。
        applyLock.withLock {
            val target = currentTarget() ?: return@withLock
            delay(ContactCollector.intervalMillis(intervalSeconds))
            if (!ContactCollector.prepare(target)) {
                WeLogger.w(TAG, "failed to prepare target ${target.name} for new friend $wxId")
                return@withLock
            }
            ContactCollector.apply(target, wxId)
            WeLogger.i(TAG, "collected new friend $wxId into ${target.name}")
        }
    }

    private suspend fun ensureBaseline() {
        while (true) {
            val done = baselineLock.withLock {
                baselineDone || mergeCurrentFriends()
            }
            if (done) return
            delay(BASELINE_RETRY_MS)
        }
    }

    /** 调用方必须持有 [baselineLock]。返回 false 表示数据库尚未就绪。 */
    private fun mergeCurrentFriends(): Boolean {
        if (!WeDatabaseApi.isReady) return false
        knownFriends = knownFriends + WeDatabaseApi.getFriends().map { it.wxId }
        baselineDone = true
        return true
    }

    private fun currentTarget(): ContactCollector.Target? {
        val kind = ContactCollector.TargetKind.entries.firstOrNull { it.name == targetKindValue } ?: return null
        if (targetName.isBlank()) return null
        if (kind != ContactCollector.TargetKind.LABEL && targetId.isBlank()) return null
        return ContactCollector.Target(kind, targetId, targetName)
    }

    private fun currentTargetSummary(context: Context): String =
        if (targetName.isBlank()) {
            context.localizedContactsString(R.string.contact_collection_no_target)
        } else {
            context.localizedContactsString(R.string.contact_collection_current_target, targetName)
        }

    private fun looksLikeFriend(username: String, values: ContentValues): Boolean {
        if (username.isBlank() || username == WeApi.selfWxId) return false
        if (username.endsWith("@chatroom") || username.startsWith("gh_")) return false
        if ((values.getAsInteger("verifyFlag") ?: 0) != 0) return false
        val type = values.getAsInteger("type") ?: return false
        return (type and 1) != 0 && (type and 8) == 0 && (type and 32) == 0
    }

    private const val UPDATE_SETTLE_MS = 800L
    private const val BASELINE_RETRY_MS = 5_000L
}
