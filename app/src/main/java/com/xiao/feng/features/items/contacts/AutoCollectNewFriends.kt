package com.xiao.feng.features.items.contacts

import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.core.WeApi
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

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
    private const val KEY_TARGETS = "auto_collect_new_friends_targets"
    private const val KEY_INTERVAL = "auto_collect_new_friends_interval_seconds"
    private const val KEY_KNOWN_FRIENDS = "auto_collect_new_friends_known"
    private const val KEY_BASELINE_DONE = "auto_collect_new_friends_baseline_done"

    override val technicalId = "加好友自动归拢"
    override val nameRes = R.string.feature_auto_collect_new_friends_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_collect_new_friends_description

    // 旧版只能配置一个目标, 保留这几个键仅用于迁移。
    private var targetKindValue by KvStore.prefOption(KEY_TARGET_KIND, "")
    private var targetId by KvStore.prefOption(KEY_TARGET_ID, "")
    private var targetName by KvStore.prefOption(KEY_TARGET_NAME, "")
    private var targetsJson by KvStore.prefOption(KEY_TARGETS, "")
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
            var extraTargets by remember { mutableStateOf<List<ContactCollector.Target>>(emptyList()) }
            var selected by remember { mutableStateOf<Set<ContactCollector.Target>>(emptySet()) }
            var intervalInput by remember { mutableStateOf(intervalSeconds) }
            var newLabelName by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                val loaded = withContext(Dispatchers.IO) {
                    ContactCollector.loadTargets(
                        context = context,
                        includeLabels = true,
                        includeCollections = true,
                    )
                }
                val saved = withContext(Dispatchers.IO) { savedTargets() }
                targets = loaded
                // 只保留仍然存在的目标, 已删除的标签 / 归拢 / 分组不再显示。
                selected = saved.filter { savedTarget ->
                    loaded.any { live -> live.matches(savedTarget) }
                }.toSet()
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_collect_new_friends_name)) },
                text = {
                    val loaded = targets
                    DefaultColumn {
                        if (loaded == null) {
                            LinearWavyProgressIndicator()
                        } else {
                            Text(targetSummary(context, selected))
                            Text(stringResource(R.string.contact_collection_multi_hint))
                            OutlinedTextField(
                                value = intervalInput,
                                onValueChange = { intervalInput = sanitizeDecimalInput(it) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.contact_collection_interval)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = newLabelName,
                                onValueChange = { newLabelName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.contact_collection_new_label_hint)) },
                                singleLine = true,
                            )
                            Button(
                                enabled = newLabelName.isNotBlank(),
                                onClick = {
                                    val name = newLabelName.trim()
                                    // 已有同名标签时直接选中它, 避免重复创建。
                                    val target = loaded.firstOrNull {
                                        it.kind == ContactCollector.TargetKind.LABEL && it.name == name
                                    } ?: ContactCollector.Target(
                                        kind = ContactCollector.TargetKind.LABEL,
                                        id = "",
                                        name = name,
                                    )
                                    if (target.id.isEmpty() && target !in extraTargets) {
                                        extraTargets = extraTargets + target
                                    }
                                    selected = selected + target
                                    newLabelName = ""
                                },
                            ) { Text(stringResource(R.string.contact_collection_new_label_add)) }
                            val displayTargets = loaded + extraTargets
                            if (displayTargets.isEmpty()) {
                                Text(stringResource(R.string.contact_collection_empty))
                            } else {
                                ContactTargetsList(
                                    targets = displayTargets,
                                    onPick = {},
                                    selectedTargets = selected,
                                    onToggle = { target ->
                                        selected = if (target in selected) selected - target else selected + target
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = targets != null,
                        onClick = {
                            val chosen = selected.toList()
                            targetsJson = encodeTargets(chosen)
                            intervalSeconds = intervalInput
                            val message = if (chosen.isEmpty()) {
                                context.localizedContactsString(R.string.contact_collection_no_target)
                            } else {
                                context.localizedContactsString(
                                    R.string.contact_collection_saved,
                                    chosen.joinToString("、") { it.name },
                                )
                            }
                            showToast(context, message)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.action_save)) }
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
            val targets = savedTargets()
            if (targets.isEmpty()) return@withLock
            val interval = ContactCollector.intervalMillis(intervalSeconds)
            targets.forEach { target ->
                delay(interval)
                if (!ContactCollector.prepare(target)) {
                    WeLogger.w(TAG, "failed to prepare target ${target.name} for new friend $wxId")
                    return@forEach
                }
                if (ContactCollector.apply(target, wxId)) {
                    WeLogger.i(TAG, "collected new friend $wxId into ${target.name}")
                } else {
                    WeLogger.w(TAG, "target ${target.name} rejected new friend $wxId")
                }
            }
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

    /** 读取全部目标; 旧版单目标配置会在第一次读取时迁移成列表。 */
    private fun savedTargets(): List<ContactCollector.Target> {
        val decoded = runCatching {
            DefaultJson.decodeFromString<List<SavedCollectTarget>>(targetsJson)
        }.getOrNull().orEmpty()
        val restored = decoded.mapNotNull { it.toTarget() }
        if (restored.isNotEmpty()) return restored
        // 空字符串表示从未配置过; "[]" 表示用户主动清空, 不再回退到旧配置。
        if (targetsJson.isNotBlank()) return emptyList()
        val legacy = legacyTarget() ?: return emptyList()
        targetsJson = encodeTargets(listOf(legacy))
        return listOf(legacy)
    }

    private fun legacyTarget(): ContactCollector.Target? {
        val kind = ContactCollector.TargetKind.entries.firstOrNull { it.name == targetKindValue } ?: return null
        if (targetName.isBlank()) return null
        if (kind != ContactCollector.TargetKind.LABEL && targetId.isBlank()) return null
        return ContactCollector.Target(kind, targetId, targetName)
    }

    private fun encodeTargets(targets: List<ContactCollector.Target>): String =
        DefaultJson.encodeToString(
            targets.map { SavedCollectTarget(it.kind.name, it.id, it.name) }
        )

    private fun targetSummary(context: Context, targets: Set<ContactCollector.Target>): String =
        if (targets.isEmpty()) {
            context.localizedContactsString(R.string.contact_collection_no_target)
        } else {
            context.localizedContactsString(
                R.string.contact_collection_current_target,
                targets.joinToString("、") { it.name },
            )
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

@Serializable
private data class SavedCollectTarget(val kind: String, val id: String, val name: String)

private fun SavedCollectTarget.toTarget(): ContactCollector.Target? {
    val kind = ContactCollector.TargetKind.entries.firstOrNull { it.name == this.kind } ?: return null
    if (name.isBlank()) return null
    if (kind != ContactCollector.TargetKind.LABEL && id.isBlank()) return null
    return ContactCollector.Target(kind, id, name)
}

/** 已保存的目标是否仍对应一个现存目标。标签按名称匹配, 因为新建标签在创建前没有 id。 */
private fun ContactCollector.Target.matches(saved: ContactCollector.Target): Boolean =
    kind == saved.kind && if (kind == ContactCollector.TargetKind.LABEL) {
        name == saved.name
    } else {
        id == saved.id
    }
