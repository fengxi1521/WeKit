package com.xiao.feng.features.items.payment

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.xiao.feng.R
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.models.IWeContact
import com.xiao.feng.data.JsonDataMigration
import com.xiao.feng.data.XiaoFengDatabase
import com.xiao.feng.data.structured.asOverrides
import com.xiao.feng.features.items.AutomationSaveContent
import com.xiao.feng.features.items.rememberAutomationSaveState
import com.xiao.feng.features.items.AutomationContactSettingsSelector
import com.xiao.feng.features.items.AutomationKeywordMode
import com.xiao.feng.features.items.AutomationKeywordRule
import com.xiao.feng.features.items.AutomationTimeRangeRule
import com.xiao.feng.features.items.AutomationToggleRule
import com.xiao.feng.features.items.automationKeywordSummary
import com.xiao.feng.features.items.formatAutomationMinute
import com.xiao.feng.data.KvStore
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseSupportingWidget
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.serialization.DefaultJson
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.Calendar
import kotlin.random.Random

/** Hierarchical settings used by [AutoOpenRedPackets]. */
object RedPacketSettings {
    private const val TAG = "RedPacketSettings"
    private const val CONFIG_VERSION = 1
    private val RED_PACKET_KEYWORD_MODES =
        listOf(AutomationKeywordMode.STRING_LIST, AutomationKeywordMode.REGEX)


    @Serializable
    enum class ReceiveMode { NETWORK, CLICK }

    @Serializable
    data class DelayRule(
        val enabled: Boolean = false,
        val baseMs: String = "0",
        val randomRangeMs: String = "0"
    )

    @Serializable
    data class ReplyRule(
        val enabled: Boolean = false,
        /** 旧版单条回复; 新配置使用 [texts]。 */
        val text: String = "",
        /** 多条回复, 抢到红包时随机抽一条发送。 */
        val texts: List<String> = emptyList(),
        /** 收到红包后延迟回复的随机范围 (秒), 两个都是 0 表示立即回复。 */
        val minDelaySeconds: String = "0",
        val maxDelaySeconds: String = "0",
    ) {
        val allTexts: List<String>
            get() = texts.map(String::trim).filter { it.isNotEmpty() }
                .ifEmpty { listOfNotNull(text.trim().takeIf { it.isNotEmpty() }) }

        fun pickText(): String? = allTexts.randomOrNull()

        fun delayMillis(): Long {
            val min = (minDelaySeconds.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val max = (maxDelaySeconds.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val low = minOf(min, max)
            val high = maxOf(min, max)
            val seconds = if (low == high) low else Random.nextLong(low, high + 1)
            return seconds * 1000L
        }
    }

    /** 群里有人发出指定文字时, 暂停自动抢红包一段时间后自动恢复。 */
    @Serializable
    data class PauseRule(
        val enabled: Boolean = false,
        val keywords: AutomationKeywordRule = AutomationKeywordRule(),
        val seconds: String = "30"
    ) {
        /** 关键词规则随暂停规则一起生效, 避免关键词规则自身的 enabled 标志遗留。 */
        val activeKeywords: AutomationKeywordRule
            get() = keywords.copy(enabled = enabled)

        fun durationMillis(): Long = (seconds.toLongOrNull() ?: 0L).coerceAtLeast(0L) * 1000L
    }

    @Serializable
    data class RuleSet(
        val grab: AutomationToggleRule = AutomationToggleRule(enabled = true),
        val grabSelf: AutomationToggleRule = AutomationToggleRule(),
        val receiveMode: ReceiveMode = ReceiveMode.NETWORK,
        val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
        val keyword: AutomationKeywordRule = AutomationKeywordRule(),
        val skipKeyword: AutomationKeywordRule = AutomationKeywordRule(),
        val pause: PauseRule = PauseRule(),
        val delay: DelayRule = DelayRule(),
        val notification: AutomationToggleRule = AutomationToggleRule(),
        val autoReply: ReplyRule = ReplyRule()
    ) {
        fun delayMillis(): Long {
            if (!delay.enabled) return 0L
            val base = (delay.baseMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val range = (delay.randomRangeMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            if (range == 0L) return base
            val safeRange = range.coerceAtMost(Long.MAX_VALUE - 1)
            val offset = Random.nextLong(-safeRange, safeRange + 1)
            return (base + offset).coerceAtLeast(0L)
        }

        fun isInActiveTime(now: Calendar = Calendar.getInstance()): Boolean = timeRange.matches(now)

        fun matchesKeyword(text: String): Boolean = keyword.matches(text)
    }

    @Serializable
    data class RuleOverrides(
        val grab: AutomationToggleRule? = null,
        val grabSelf: AutomationToggleRule? = null,
        val receiveMode: ReceiveMode? = null,
        val timeRange: AutomationTimeRangeRule? = null,
        val keyword: AutomationKeywordRule? = null,
        val skipKeyword: AutomationKeywordRule? = null,
        val pause: PauseRule? = null,
        val delay: DelayRule? = null,
        val notification: AutomationToggleRule? = null,
        val autoReply: ReplyRule? = null
    ) {
        fun isEmpty(): Boolean = overriddenCount() == 0

        fun overriddenCount(): Int = listOf(
            grab,
            grabSelf,
            receiveMode,
            timeRange,
            keyword,
            skipKeyword,
            pause,
            delay,
            notification,
            autoReply
        ).count { it != null }
    }

    @Serializable
    data class StoredConfig(
        val version: Int = CONFIG_VERSION,
        val global: RuleSet = RuleSet(),
        val contacts: Map<String, RuleOverrides> = emptyMap(),
        val groupMembers: Map<String, Map<String, RuleOverrides>> = emptyMap()
    )

    @Serializable
    private data class LegacyGroupMemberRule(
        val groupId: String = "",
        val useWhitelist: Boolean = false,
        val members: List<String> = emptyList()
    )

    private enum class RuleKey {
        GRAB,
        GRAB_SELF,
        RECEIVE_MODE,
        TIME_RANGE,
        KEYWORD,
        SKIP_KEYWORD,
        PAUSE,
        DELAY,
        NOTIFICATION,
        AUTO_REPLY
    }

    @Volatile
    private var cachedConfig: StoredConfig? = null

    /** 写配置的进程会递增该值; 其他进程 (如 :push) 据此丢弃过期缓存。 */
    private var configRevision by prefOption("red_packet_config_revision", 0L)

    @Volatile
    private var cachedConfigRevision = -1L

    private var legacyDelayReset by prefOption("red_packet_legacy_delay_reset", false)

    /** 新进群保护: 开启后, 之后新出现的群默认不抢红包, 仍可在群设置里单独打开。 */
    var blockNewGroups: Boolean by prefOption("red_packet_block_new_groups", false)

    private var groupBaselineDone: Boolean by prefOption("red_packet_group_baseline_done", false)

    /** 已见过的群聊, 用于识别新进群。 */
    private var knownGroups: Set<String> by prefOption("red_packet_known_groups", emptySet<String>())

    private var statsRaw: String by prefOption("red_packet_stats", "")

    /** 抢红包统计 (累计与当天), 对应 Hchat 红包面板里的统计信息。 */
    @Serializable
    data class Stats(
        val count: Int = 0,
        val amountCents: Long = 0L,
        val todayCount: Int = 0,
        val todayAmountCents: Long = 0L,
        val today: String = "",
    )

    fun stats(): Stats {
        val raw = statsRaw
        if (raw.isBlank()) return Stats()
        return runCatching { DefaultJson.decodeFromString<Stats>(raw) }.getOrDefault(Stats())
    }

    fun recordGrab(amountCents: Long) {
        val today = LocalDate.now().toString()
        val current = stats()
        val base = if (current.today == today) current else current.copy(todayCount = 0, todayAmountCents = 0)
        statsRaw = DefaultJson.encodeToString(
            base.copy(
                count = base.count + 1,
                amountCents = base.amountCents + amountCents,
                todayCount = base.todayCount + 1,
                todayAmountCents = base.todayAmountCents + amountCents,
                today = today,
            ),
        )
    }

    /** 首次运行时把现有群聊记为已知, 避免把老群误判成新进群。 */
    fun ensureGroupBaseline() {
        if (groupBaselineDone) return
        // 宿主数据库只在主进程可用; 其他进程不能替主进程写这份基线。
        if (!TargetProcesses.isInMain) return
        runBlocking(Dispatchers.IO) {
            try {
                knownGroups = WeDatabaseApi.getGroups()
                    .map { it.wxId }
                    .filter { it.isGroupChatWxId }
                    .toSet()
                groupBaselineDone = true
                WeLogger.i(TAG, "preloaded ${knownGroups.size} known groups for new-group protection")
            } catch (error: Exception) {
                WeLogger.w(TAG, "failed to preload known groups", error)
            }
        }
    }

    /** 记录群聊; 返回 true 表示第一次见到 (新进群)。 */
    fun registerGroup(groupId: String): Boolean {
        val known = knownGroups
        if (groupId in known) return false
        knownGroups = known + groupId
        return true
    }

    fun invalidateCache() {
        cachedConfig = null
    }

    fun requireReady() {
        loadConfig()
        resetUntouchedLegacyDelay()
    }

    /**
     * Old builds shipped a 500ms ±300ms random delay as the default. Grab requests should go out
     * immediately, so an untouched global delay is reset once; custom values are left alone.
     */
    private fun resetUntouchedLegacyDelay() {
        // 一次性迁移只归主进程负责, 避免 push 进程与主进程同时改写配置。
        if (!TargetProcesses.isInMain) return
        if (legacyDelayReset) return
        val config = loadConfig()
        val delay = config.global.delay
        if (!delay.enabled || delay.baseMs != "500" || delay.randomRangeMs != "300") {
            legacyDelayReset = true
            return
        }
        runBlocking(Dispatchers.IO) {
            try {
                val updated = config.global
                    .copy(delay = DelayRule())
                    .asOverrides()
                XiaoFengDatabase.instance.automationDao().putRedPacketRule("GLOBAL", "", "", updated)
                legacyDelayReset = true
                WeLogger.i(TAG, "reset untouched legacy 500±300ms delay to immediate grab")
            } catch (error: Exception) {
                WeLogger.w(TAG, "failed to reset legacy red packet delay", error)
            } finally {
                cachedConfig = null
                configRevision += 1
            }
        }
    }

    fun resolve(talker: String, sender: String?): RuleSet {
        val config = loadConfig()
        var rules = config.global.apply(config.contacts[talker])
        if (talker.isGroupChatWxId && !sender.isNullOrBlank()) {
            rules = rules.apply(config.groupMembers[talker]?.get(sender))
        }
        return rules
    }

    fun showMainDialog(context: Context) {
        if (!JsonDataMigration.isCompleted("json", "RedPacketSettings")) {
            showToast(context, context.localizedPaymentString(R.string.structured_storage_unavailable))
            return
        }
        showComposeDialog(context) {
            var blockNewGroupsInput by remember { mutableStateOf(blockNewGroups) }
            val statsSnapshot = remember { RedPacketSettings.stats() }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_open_red_packets_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_global_settings),
                                description = stringResource(R.string.payment_red_packet_global_summary),
                                onClick = { showGlobalDialog(context) },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_contact_settings),
                                description = stringResource(R.string.automation_contact_settings_summary),
                                onClick = { showContactSelector(context) },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.payment_red_packet_block_new_groups),
                                description = stringResource(R.string.payment_red_packet_block_new_groups_description),
                                checked = blockNewGroupsInput,
                                onCheckedChange = {
                                    blockNewGroupsInput = it
                                    blockNewGroups = it
                                    if (it) ensureGroupBaseline()
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.payment_red_packet_stats_title),
                                description = stringResource(
                                    R.string.payment_red_packet_stats,
                                    statsSnapshot.count,
                                    statsSnapshot.amountCents / 100.0,
                                    statsSnapshot.todayCount,
                                    statsSnapshot.todayAmountCents / 100.0,
                                ),
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    private fun showGlobalDialog(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalXiaoFengLocalizedContext.current)
            val saveState = rememberAutomationSaveState()
            androidx.compose.runtime.SideEffect { dialog.setCancelable(!saveState.saving) }
            var draft by remember { mutableStateOf(globalRules()) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val validationError = validate(localizedContext, draft)

            val editMode = editText
            if (editMode != null) {
                PaymentTextEditDialog(editMode, onClose = { editText = null })
                return@showComposeDialog
            }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(stringResource(R.string.automation_global_settings)) },
                text = {
                    AutomationSaveContent(saveState) {
                        RuleSetEditor(
                            rules = draft,
                            overriddenKeys = null,
                            parentLabel = "",
                            onActivate = {},
                            onReset = {},
                            onChange = { _, updated -> draft = updated },
                            validationError = validationError,
                            onEditText = { editText = it },
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null && !saveState.saving,
                        onClick = {
                            saveState.submit(save = { putRule("GLOBAL", "", "", draft.asOverrides()) }, onSuccess = {
                                showToast(localizedContext.getString(R.string.automation_global_settings_saved))
                                onDismiss()
                            })
                        }
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss, enabled = !saveState.saving) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    private fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalXiaoFengLocalizedContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            AutomationContactSettingsSelector(
                title = stringResource(R.string.automation_contact_settings),
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val count = contactOverrides(contact.wxId).overriddenCount()
                    when {
                        contact.wxId.isGroupChatWxId && count > 0 ->
                            localizedContext.resources.getQuantityString(
                                R.plurals.automation_group_overrides,
                                count,
                                count,
                            )
                        contact.wxId.isGroupChatWxId -> localizedContext.getString(R.string.automation_group_settings)
                        count > 0 -> localizedContext.resources.getQuantityString(
                            R.plurals.automation_overrides,
                            count,
                            count,
                        )
                        else -> localizedContext.getString(R.string.automation_follow_global)
                    }
                },
                isConfigured = { contact ->
                    contactOverrides(contact.wxId).overriddenCount() > 0 ||
                            memberOverridesCount(contact.wxId) > 0
                },
                onDismiss = onDismiss,
                onOpen = { contact ->
                    if (contact.wxId.isGroupChatWxId) {
                        showGroupSettingsDialog(context, contact.wxId) { revision++ }
                    } else {
                        showOverrideDialog(
                            context = context,
                            title = PaymentUiText.Raw(contact.displayName.ifBlank { contact.wxId }),
                            parentLabelRes = R.string.automation_global_settings,
                            parent = globalRules(),
                            initial = contactOverrides(contact.wxId),
                            onSave = {
                                setContactOverrides(contact.wxId, it)
                                revision++
                            }
                        )
                    }
                }
            )
        }
    }

    private fun showGroupSettingsDialog(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val groupOverrideCount = remember(revision) {
                contactOverrides(groupId).overriddenCount()
            }
            val memberCount = remember(revision) { memberOverridesCount(groupId) }

            AlertDialogContent(
                title = { Text(groupName) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_group_global_settings),
                                description = if (groupOverrideCount == 0) {
                                    stringResource(R.string.automation_follow_global)
                                } else {
                                    pluralStringResource(
                                        R.plurals.automation_overrides,
                                        groupOverrideCount,
                                        groupOverrideCount,
                                    )
                                },
                                onClick = {
                                    showOverrideDialog(
                                        context = context,
                                        title = PaymentUiText.Resource(R.string.automation_group_global_settings),
                                        parentLabelRes = R.string.automation_global_settings,
                                        parent = globalRules(),
                                        initial = contactOverrides(groupId),
                                        onSave = {
                                            setContactOverrides(groupId, it)
                                            revision++
                                            onUpdated()
                                        }
                                    )
                                },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = stringResource(R.string.automation_group_member_settings),
                                description = if (memberCount == 0) {
                                    stringResource(R.string.automation_all_members_follow_group)
                                } else {
                                    pluralStringResource(
                                        R.plurals.automation_configured_members,
                                        memberCount,
                                        memberCount,
                                    )
                                },
                                onClick = {
                                    showGroupMemberSelector(context, groupId) {
                                        revision++
                                        onUpdated()
                                    }
                                },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }

    private fun showGroupMemberSelector(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalXiaoFengLocalizedContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val members = remember(groupId) {
                runCatching { WeDatabaseApi.getGroupMembers(groupId) }
                    .onFailure { WeLogger.e(TAG, "failed to load members of $groupId", it) }
                    .getOrDefault(emptyList())
            }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }

            AutomationContactSettingsSelector(
                title = stringResource(R.string.automation_group_member_settings_title, groupName),
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = groupMemberOverrides(groupId, member.wxId).overriddenCount()
                    if (count == 0) {
                        localizedContext.getString(R.string.automation_follow_group_global)
                    } else {
                        localizedContext.resources.getQuantityString(
                            R.plurals.automation_overrides,
                            count,
                            count,
                        )
                    }
                },
                isConfigured = { member ->
                    groupMemberOverrides(groupId, member.wxId).overriddenCount() > 0
                },
                onDismiss = onDismiss,
                onOpen = { member ->
                    showOverrideDialog(
                        context = context,
                        title = PaymentUiText.Raw(member.displayName.ifBlank { member.wxId }),
                        parentLabelRes = R.string.automation_group_global_settings,
                        parent = globalRules().apply(contactOverrides(groupId)),
                        initial = groupMemberOverrides(groupId, member.wxId),
                        onSave = {
                            setGroupMemberOverrides(groupId, member.wxId, it)
                            revision++
                            onUpdated()
                        }
                    )
                }
            )
        }
    }

    private fun showOverrideDialog(
        context: Context,
        title: PaymentUiText,
        @androidx.annotation.StringRes parentLabelRes: Int,
        parent: RuleSet,
        initial: RuleOverrides,
        onSave: suspend (RuleOverrides) -> Unit
    ) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalXiaoFengLocalizedContext.current)
            val saveState = rememberAutomationSaveState()
            androidx.compose.runtime.SideEffect { dialog.setCancelable(!saveState.saving) }
            var draft by remember { mutableStateOf(initial) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val effective = parent.apply(draft)
            val validationError = validate(localizedContext, effective, draft.keys())

            val editMode = editText
            if (editMode != null) {
                PaymentTextEditDialog(editMode, onClose = { editText = null })
                return@showComposeDialog
            }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(title.resolve()) },
                text = {
                    AutomationSaveContent(saveState) {
                        RuleSetEditor(
                            rules = effective,
                            overriddenKeys = draft.keys(),
                            parentLabel = stringResource(parentLabelRes),
                            onActivate = { key -> draft = draft.withRule(key, effective) },
                            onReset = { key -> draft = draft.withoutRule(key) },
                            onChange = { key, updated -> draft = draft.withRule(key, updated) },
                            validationError = validationError,
                            onEditText = { editText = it },
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null && !saveState.saving,
                        onClick = {
                            saveState.submit(save = { onSave(draft) }, onSuccess = {
                                showToast(localizedContext.getString(R.string.settings_saved))
                                onDismiss()
                            })
                        }
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss, enabled = !saveState.saving) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    @Composable
    private fun RuleSetEditor(
        rules: RuleSet,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onChange: (RuleKey, RuleSet) -> Unit,
        validationError: String?,
        onEditText: (PaymentTextEditMode) -> Unit
    ) {
        val isGlobalEditor = overriddenKeys == null

        fun overridden(key: RuleKey): Boolean? = overriddenKeys?.let { key in it }
        fun editable(key: RuleKey): Boolean = overriddenKeys == null || key in overriddenKeys

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                item(key = "grab") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_default_grab),
                        summary = if (isGlobalEditor && rules.grab.enabled) {
                            stringResource(R.string.payment_red_packet_default_all)
                        } else if (isGlobalEditor) {
                            stringResource(R.string.payment_red_packet_default_none)
                        } else if (rules.grab.enabled) {
                            stringResource(R.string.payment_red_packet_grab_scope)
                        } else {
                            stringResource(R.string.payment_red_packet_skip_scope)
                        },
                        checked = rules.grab.enabled,
                        overridden = overridden(RuleKey.GRAB),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.GRAB) },
                        onReset = { onReset(RuleKey.GRAB) },
                        onCheckedChange = {
                            onChange(RuleKey.GRAB, rules.copy(grab = rules.grab.copy(enabled = it)))
                        },
                    )
                }

                item(key = "grab_self") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_grab_self),
                        summary = stringResource(
                            if (rules.grabSelf.enabled) {
                                R.string.payment_red_packet_grab_self_enabled
                            } else {
                                R.string.payment_red_packet_grab_self_disabled
                            }
                        ),
                        checked = rules.grabSelf.enabled,
                        overridden = overridden(RuleKey.GRAB_SELF),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.GRAB_SELF) },
                        onReset = { onReset(RuleKey.GRAB_SELF) },
                        onCheckedChange = {
                            onChange(RuleKey.GRAB_SELF, rules.copy(grabSelf = rules.grabSelf.copy(enabled = it)))
                        },
                    )
                }

                item(key = "receive_mode") {
                    PaymentModeRuleRow(
                        title = stringResource(R.string.payment_red_packet_receive_mode),
                        summary = if (rules.receiveMode == ReceiveMode.NETWORK) {
                            stringResource(R.string.payment_red_packet_network_default)
                        } else {
                            stringResource(R.string.payment_red_packet_manual_click)
                        },
                        overridden = overridden(RuleKey.RECEIVE_MODE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.RECEIVE_MODE) },
                        onReset = { onReset(RuleKey.RECEIVE_MODE) },
                    )
                }
                ReceiveMode.entries.forEach { mode ->
                    item(key = "receive_mode_${mode.name}") {
                        RadioButtonWidget(
                            iconPlaceholder = false,
                            title = stringResource(
                                if (mode == ReceiveMode.NETWORK) {
                                    R.string.payment_red_packet_network
                                } else {
                                    R.string.payment_red_packet_manual_click
                                }
                            ),
                            selected = rules.receiveMode == mode,
                            enabled = editable(RuleKey.RECEIVE_MODE),
                            onClick = { onChange(RuleKey.RECEIVE_MODE, rules.copy(receiveMode = mode)) },
                        )
                    }
                }

                item(key = "time_range") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_time_range),
                        summary = if (rules.timeRange.enabled) {
                            "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                        } else {
                            stringResource(R.string.payment_red_packet_time_unrestricted)
                        },
                        checked = rules.timeRange.enabled,
                        overridden = overridden(RuleKey.TIME_RANGE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.TIME_RANGE) },
                        onReset = { onReset(RuleKey.TIME_RANGE) },
                        onCheckedChange = {
                            onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = rules.timeRange.copy(enabled = it)))
                        },
                    )
                }
                timeRangeItems(
                    rule = rules.timeRange,
                    editable = editable(RuleKey.TIME_RANGE),
                    visible = rules.timeRange.enabled,
                    onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) },
                )

                item(key = "keyword") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_keywords),
                        summary = automationKeywordSummary(
                            rules.keyword,
                            stringResource(R.string.payment_red_packet_keywords_unrestricted),
                        ),
                        checked = rules.keyword.enabled,
                        overridden = overridden(RuleKey.KEYWORD),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.KEYWORD) },
                        onReset = { onReset(RuleKey.KEYWORD) },
                        onCheckedChange = {
                            onChange(RuleKey.KEYWORD, rules.copy(keyword = rules.keyword.copy(enabled = it)))
                        },
                    )
                }
                keywordItems(
                    keyPrefix = "keyword",
                    rule = rules.keyword,
                    editable = editable(RuleKey.KEYWORD),
                    visible = rules.keyword.enabled,
                    modes = RED_PACKET_KEYWORD_MODES,
                    onChange = { onChange(RuleKey.KEYWORD, rules.copy(keyword = it)) },
                    onEditText = onEditText,
                )

                item(key = "skip_keyword") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_skip_keywords),
                        summary = automationKeywordSummary(
                            rules.skipKeyword,
                            stringResource(R.string.payment_red_packet_skip_keywords_unrestricted),
                        ),
                        checked = rules.skipKeyword.enabled,
                        overridden = overridden(RuleKey.SKIP_KEYWORD),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.SKIP_KEYWORD) },
                        onReset = { onReset(RuleKey.SKIP_KEYWORD) },
                        onCheckedChange = {
                            onChange(
                                RuleKey.SKIP_KEYWORD,
                                rules.copy(skipKeyword = rules.skipKeyword.copy(enabled = it)),
                            )
                        },
                    )
                }
                keywordItems(
                    keyPrefix = "skip_keyword",
                    rule = rules.skipKeyword,
                    editable = editable(RuleKey.SKIP_KEYWORD),
                    visible = rules.skipKeyword.enabled,
                    modes = RED_PACKET_KEYWORD_MODES,
                    onChange = { onChange(RuleKey.SKIP_KEYWORD, rules.copy(skipKeyword = it)) },
                    onEditText = onEditText,
                )

                item(key = "pause") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_pause_keywords),
                        summary = if (rules.pause.enabled) {
                            stringResource(
                                R.string.payment_red_packet_pause_summary,
                                rules.pause.seconds.ifBlank { "0" },
                            )
                        } else {
                            stringResource(R.string.payment_red_packet_pause_unrestricted)
                        },
                        checked = rules.pause.enabled,
                        overridden = overridden(RuleKey.PAUSE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.PAUSE) },
                        onReset = { onReset(RuleKey.PAUSE) },
                        onCheckedChange = {
                            onChange(RuleKey.PAUSE, rules.copy(pause = rules.pause.copy(enabled = it)))
                        },
                    )
                }
                keywordItems(
                    keyPrefix = "pause",
                    rule = rules.pause.keywords,
                    editable = editable(RuleKey.PAUSE),
                    visible = rules.pause.enabled,
                    modes = RED_PACKET_KEYWORD_MODES,
                    onChange = { onChange(RuleKey.PAUSE, rules.copy(pause = rules.pause.copy(keywords = it))) },
                    onEditText = onEditText,
                )
                item(key = "pause_seconds", animatedVisibility = rules.pause.enabled) {
                    val secondsTitle = stringResource(R.string.payment_red_packet_pause_seconds)
                    PaymentValueRow(
                        title = secondsTitle,
                        value = rules.pause.seconds,
                        enabled = editable(RuleKey.PAUSE),
                        valueHint = "30",
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = secondsTitle,
                                    initial = rules.pause.seconds,
                                    keyboardType = KeyboardType.Number,
                                    filter = { it.filter(Char::isDigit).take(5) },
                                    onCommit = {
                                        onChange(
                                            RuleKey.PAUSE,
                                            rules.copy(pause = rules.pause.copy(seconds = it)),
                                        )
                                    },
                                )
                            )
                        },
                    )
                }

                item(key = "delay") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_delay),
                        summary = if (rules.delay.enabled) {
                            stringResource(
                                R.string.automation_delay_summary,
                                rules.delay.baseMs.ifBlank { "0" },
                                rules.delay.randomRangeMs.ifBlank { "0" },
                            )
                        } else {
                            stringResource(R.string.payment_red_packet_immediate)
                        },
                        checked = rules.delay.enabled,
                        overridden = overridden(RuleKey.DELAY),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.DELAY) },
                        onReset = { onReset(RuleKey.DELAY) },
                        onCheckedChange = {
                            onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(enabled = it)))
                        },
                    )
                }
                delayItems(
                    baseMs = rules.delay.baseMs,
                    randomRangeMs = rules.delay.randomRangeMs,
                    editable = editable(RuleKey.DELAY),
                    visible = rules.delay.enabled,
                    maxDigits = MAX_DELAY_DIGITS,
                    onBaseChange = {
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(baseMs = it)))
                    },
                    onRandomRangeChange = {
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(randomRangeMs = it)))
                    },
                    onEditText = onEditText,
                )

                item(key = "notification") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_notification),
                        summary = stringResource(
                            if (rules.notification.enabled) {
                                R.string.payment_red_packet_notification_enabled
                            } else {
                                R.string.automation_notification_disabled
                            }
                        ),
                        checked = rules.notification.enabled,
                        overridden = overridden(RuleKey.NOTIFICATION),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.NOTIFICATION) },
                        onReset = { onReset(RuleKey.NOTIFICATION) },
                        onCheckedChange = {
                            onChange(
                                RuleKey.NOTIFICATION,
                                rules.copy(notification = rules.notification.copy(enabled = it)),
                            )
                        },
                    )
                }

                item(key = "auto_reply") {
                    PaymentRuleRow(
                        title = stringResource(R.string.payment_red_packet_auto_reply),
                        summary = stringResource(
                            if (rules.autoReply.enabled) {
                                R.string.automation_auto_reply_enabled
                            } else {
                                R.string.automation_auto_reply_disabled
                            }
                        ),
                        checked = rules.autoReply.enabled,
                        overridden = overridden(RuleKey.AUTO_REPLY),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.AUTO_REPLY) },
                        onReset = { onReset(RuleKey.AUTO_REPLY) },
                        onCheckedChange = {
                            onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(enabled = it)))
                        },
                    )
                }
                item(key = "auto_reply_texts", animatedVisibility = rules.autoReply.enabled) {
                    val repliesTitle = stringResource(R.string.payment_red_packet_auto_reply_texts)
                    val addTitle = stringResource(R.string.payment_red_packet_auto_reply_add)
                    BaseSupportingWidget(title = repliesTitle) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            rules.autoReply.allTexts.forEachIndexed { index, reply ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = reply,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        enabled = editable(RuleKey.AUTO_REPLY),
                                        onClick = {
                                            val updated = rules.autoReply.allTexts.toMutableList()
                                                .apply { removeAt(index) }
                                            onChange(
                                                RuleKey.AUTO_REPLY,
                                                rules.copy(
                                                    autoReply = rules.autoReply.copy(
                                                        texts = updated,
                                                        text = "",
                                                    )
                                                )
                                            )
                                        },
                                    ) {
                                        Icon(MaterialSymbols.Outlined.Delete, null)
                                    }
                                }
                            }
                            TextButton(
                                enabled = editable(RuleKey.AUTO_REPLY),
                                onClick = {
                                    onEditText(
                                        PaymentTextEditMode(
                                            title = addTitle,
                                            initial = "",
                                            onCommit = { input ->
                                                val reply = input.trim()
                                                if (reply.isNotEmpty() && reply !in rules.autoReply.allTexts) {
                                                    onChange(
                                                        RuleKey.AUTO_REPLY,
                                                        rules.copy(
                                                            autoReply = rules.autoReply.copy(
                                                                texts = rules.autoReply.allTexts + reply,
                                                                text = "",
                                                            )
                                                        )
                                                    )
                                                }
                                            },
                                        )
                                    )
                                },
                            ) {
                                Icon(MaterialSymbols.Outlined.Add, null)
                                Text(addTitle)
                            }
                        }
                    }
                }

                item(key = "auto_reply_delay_min", animatedVisibility = rules.autoReply.enabled) {
                    val delayMinTitle = stringResource(R.string.payment_red_packet_reply_delay_min)
                    PaymentValueRow(
                        title = delayMinTitle,
                        value = rules.autoReply.minDelaySeconds,
                        enabled = editable(RuleKey.AUTO_REPLY),
                        valueHint = "0",
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = delayMinTitle,
                                    initial = rules.autoReply.minDelaySeconds,
                                    keyboardType = KeyboardType.Number,
                                    filter = { it.filter(Char::isDigit).take(4) },
                                    onCommit = {
                                        onChange(
                                            RuleKey.AUTO_REPLY,
                                            rules.copy(
                                                autoReply = rules.autoReply.copy(
                                                    minDelaySeconds = it
                                                )
                                            )
                                        )
                                    },
                                )
                            )
                        },
                    )
                }
                item(key = "auto_reply_delay_max", animatedVisibility = rules.autoReply.enabled) {
                    val delayMaxTitle = stringResource(R.string.payment_red_packet_reply_delay_max)
                    PaymentValueRow(
                        title = delayMaxTitle,
                        value = rules.autoReply.maxDelaySeconds,
                        enabled = editable(RuleKey.AUTO_REPLY),
                        valueHint = "0",
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = delayMaxTitle,
                                    initial = rules.autoReply.maxDelaySeconds,
                                    keyboardType = KeyboardType.Number,
                                    filter = { it.filter(Char::isDigit).take(4) },
                                    onCommit = {
                                        onChange(
                                            RuleKey.AUTO_REPLY,
                                            rules.copy(
                                                autoReply = rules.autoReply.copy(
                                                    maxDelaySeconds = it
                                                )
                                            )
                                        )
                                    },
                                )
                            )
                        },
                    )
                }

                if (validationError != null) {
                    item(key = "validation_error") { PaymentErrorRow(validationError) }
                }
            }
        }
    }

    private fun RuleSet.apply(overrides: RuleOverrides?): RuleSet {
        if (overrides == null) return this
        return copy(
            grab = overrides.grab ?: grab,
            grabSelf = overrides.grabSelf ?: grabSelf,
            receiveMode = overrides.receiveMode ?: receiveMode,
            timeRange = overrides.timeRange ?: timeRange,
            keyword = overrides.keyword ?: keyword,
            skipKeyword = overrides.skipKeyword ?: skipKeyword,
            pause = overrides.pause ?: pause,
            delay = overrides.delay ?: delay,
            notification = overrides.notification ?: notification,
            autoReply = overrides.autoReply ?: autoReply
        )
    }

    private fun RuleOverrides.keys(): Set<RuleKey> = buildSet {
        if (grab != null) add(RuleKey.GRAB)
        if (grabSelf != null) add(RuleKey.GRAB_SELF)
        if (receiveMode != null) add(RuleKey.RECEIVE_MODE)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (keyword != null) add(RuleKey.KEYWORD)
        if (skipKeyword != null) add(RuleKey.SKIP_KEYWORD)
        if (pause != null) add(RuleKey.PAUSE)
        if (delay != null) add(RuleKey.DELAY)
        if (notification != null) add(RuleKey.NOTIFICATION)
        if (autoReply != null) add(RuleKey.AUTO_REPLY)
    }

    private fun RuleOverrides.withRule(key: RuleKey, rules: RuleSet): RuleOverrides = when (key) {
        RuleKey.GRAB -> copy(grab = rules.grab)
        RuleKey.GRAB_SELF -> copy(grabSelf = rules.grabSelf)
        RuleKey.RECEIVE_MODE -> copy(receiveMode = rules.receiveMode)
        RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
        RuleKey.KEYWORD -> copy(keyword = rules.keyword)
        RuleKey.SKIP_KEYWORD -> copy(skipKeyword = rules.skipKeyword)
        RuleKey.PAUSE -> copy(pause = rules.pause)
        RuleKey.DELAY -> copy(delay = rules.delay)
        RuleKey.NOTIFICATION -> copy(notification = rules.notification)
        RuleKey.AUTO_REPLY -> copy(autoReply = rules.autoReply)
    }

    private fun RuleOverrides.withoutRule(key: RuleKey): RuleOverrides = when (key) {
        RuleKey.GRAB -> copy(grab = null)
        RuleKey.GRAB_SELF -> copy(grabSelf = null)
        RuleKey.RECEIVE_MODE -> copy(receiveMode = null)
        RuleKey.TIME_RANGE -> copy(timeRange = null)
        RuleKey.KEYWORD -> copy(keyword = null)
        RuleKey.SKIP_KEYWORD -> copy(skipKeyword = null)
        RuleKey.PAUSE -> copy(pause = null)
        RuleKey.DELAY -> copy(delay = null)
        RuleKey.NOTIFICATION -> copy(notification = null)
        RuleKey.AUTO_REPLY -> copy(autoReply = null)
    }

    private fun validate(context: Context, rules: RuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys

        if (validates(RuleKey.DELAY) && rules.delay.enabled) {
            if (rules.delay.baseMs.toLongOrNull() == null) {
                return context.getString(R.string.automation_invalid_base_delay)
            }
            if (rules.delay.randomRangeMs.toLongOrNull() == null) {
                return context.getString(R.string.automation_invalid_random_delay)
            }
        }
        if (validates(RuleKey.KEYWORD)) {
            rules.keyword.validationError(context.getString(R.string.payment_red_packet_keyword_label))?.let { return it }
        }
        if (validates(RuleKey.SKIP_KEYWORD)) {
            rules.skipKeyword.validationError(context.getString(R.string.payment_red_packet_skip_keyword_label))?.let { return it }
        }
        if (validates(RuleKey.PAUSE) && rules.pause.enabled) {
            rules.pause.activeKeywords.validationError(
                context.getString(R.string.payment_red_packet_pause_keyword_label)
            )?.let { return it }
            val seconds = rules.pause.seconds.toLongOrNull()
            if (seconds == null || seconds <= 0) {
                return context.getString(R.string.payment_red_packet_pause_invalid_seconds)
            }
        }
        if (validates(RuleKey.AUTO_REPLY) && rules.autoReply.enabled && rules.autoReply.allTexts.isEmpty()) {
            return context.getString(R.string.automation_reply_required)
        }
        return null
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups())
            .distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(TAG, "failed to load contacts", it)
    }.getOrDefault(emptyList())

    private fun globalRules(): RuleSet = loadConfig().global

    private fun contactOverrides(wxId: String): RuleOverrides =
        loadConfig().contacts[wxId] ?: RuleOverrides()

    private fun groupMemberOverrides(groupId: String, memberId: String): RuleOverrides =
        loadConfig().groupMembers[groupId]?.get(memberId) ?: RuleOverrides()

    private fun memberOverridesCount(groupId: String): Int =
        loadConfig().groupMembers[groupId]?.count { !it.value.isEmpty() } ?: 0

    private suspend fun setContactOverrides(wxId: String, overrides: RuleOverrides) {
        putRule("CONTACT", wxId, "", overrides)
    }

    /** 新进群保护: 给该会话写入“不抢红包”规则, 用户之后可在群设置里改回来。 */
    suspend fun disableGrabForContact(wxId: String) {
        setContactOverrides(wxId, RuleOverrides(grab = AutomationToggleRule(enabled = false)))
    }

    private suspend fun setGroupMemberOverrides(groupId: String, memberId: String, overrides: RuleOverrides) {
        putRule("GROUP_MEMBER", groupId, memberId, overrides)
    }

    private fun loadConfig(): StoredConfig {
        JsonDataMigration.requireCompleted("json", "RedPacketSettings")
        val revision = configRevision
        cachedConfig?.let { if (cachedConfigRevision == revision) return it }
        return runBlocking(Dispatchers.IO) {
            XiaoFengDatabase.instance.automationDao().getRedPacketConfig()
                .also {
                    cachedConfig = it
                    cachedConfigRevision = revision
                }
        }
    }

    private suspend fun putRule(scopeType: String, talkerId: String, memberId: String, rules: RuleOverrides) {
        JsonDataMigration.requireCompleted("json", "RedPacketSettings")
        withContext(Dispatchers.IO) {
            try {
                XiaoFengDatabase.instance.automationDao().putRedPacketRule(scopeType, talkerId, memberId, rules)
            } finally {
                // Also clear on cancellation: the SQL transaction may already have committed.
                cachedConfig = null
                configRevision += 1
            }
        }
    }

    fun migrateLegacyConfig(legacyGroupMembersRaw: String?): StoredConfig {
        KvStore.requireMigrationKeys(legacyPreferenceKeys)
        val hasLegacyPrefs = legacyPreferenceKeys.any(KvStore::containsKey)
        val legacyUseWhitelist = hasLegacyPrefs &&
                KvStore.getBoolOrDef("red_packet_use_whitelist", false)
        val legacySelectedContacts = if (!hasLegacyPrefs) {
            emptySet()
        } else if (legacyUseWhitelist) {
            KvStore.getStringSetOrDef("red_packet_whitelist", emptySet())
        } else {
            KvStore.getStringSetOrDef("red_packet_blacklist", emptySet())
        }
        val legacyDelayRange = KvStore.getStringOrDef("red_packet_delay_random_range", "300")
        val legacyDelayBase = if (KvStore.containsKey("red_packet_delay_custom")) {
            KvStore.getStringOrDef("red_packet_delay_custom", "0")
        } else {
            "500"
        }
        val migratedDelayBase = if (
            legacyDelayRange.toLongOrNull() ?: 0L > 0L &&
            legacyDelayBase.toLongOrNull() ?: 0L <= 0L
        ) {
            "1000"
        } else {
            legacyDelayBase
        }
        val global = if (hasLegacyPrefs) {
            RuleSet(
                grab = AutomationToggleRule(enabled = !legacyUseWhitelist),
                grabSelf = AutomationToggleRule(KvStore.getBoolOrDef("red_packet_self", false)),
                delay = DelayRule(
                    enabled = true,
                    baseMs = migratedDelayBase,
                    randomRangeMs = legacyDelayRange
                ),
                notification = AutomationToggleRule(KvStore.getBoolOrDef("red_packet_notification", false)),
                autoReply = KvStore.getStringOrDef("red_packet_auto_reply", "").let {
                    ReplyRule(enabled = it.isNotBlank(), text = it)
                }
            )
        } else {
            RuleSet()
        }

        val contacts = mutableMapOf<String, RuleOverrides>()
        if (hasLegacyPrefs) {
            legacySelectedContacts.forEach { wxId ->
                contacts[wxId] = RuleOverrides(grab = AutomationToggleRule(enabled = legacyUseWhitelist))
            }
        }

        val groupMembers = mutableMapOf<String, MutableMap<String, RuleOverrides>>()
        val legacyGroupRules = if (legacyGroupMembersRaw == null) emptyList() else {
            DefaultJson.decodeFromString<List<LegacyGroupMemberRule>>(legacyGroupMembersRaw)
        }

        legacyGroupRules.forEach { rule ->
            if (!rule.groupId.isGroupChatWxId) return@forEach
            val conversationWasAllowed = if (legacyUseWhitelist) {
                rule.groupId in legacySelectedContacts
            } else {
                rule.groupId !in legacySelectedContacts
            }
            if (!conversationWasAllowed) return@forEach
            if (rule.useWhitelist) {
                val existing = contacts[rule.groupId] ?: RuleOverrides()
                contacts[rule.groupId] = existing.copy(grab = AutomationToggleRule(enabled = false))
            }
            val memberRules = groupMembers.getOrPut(rule.groupId) { mutableMapOf() }
            rule.members.filter(String::isNotBlank).forEach { memberId ->
                memberRules[memberId] = RuleOverrides(
                    grab = AutomationToggleRule(enabled = rule.useWhitelist)
                )
            }
        }

        if (hasLegacyPrefs || legacyGroupRules.isNotEmpty()) {
            WeLogger.i(TAG, "migrated legacy red packet settings")
        }
        return StoredConfig(
            global = global,
            contacts = contacts,
            groupMembers = groupMembers
        )
    }

    private const val MAX_DELAY_DIGITS = 7
    private val legacyPreferenceKeys = listOf(
        "red_packet_notification",
        "red_packet_self",
        "red_packet_use_whitelist",
        "red_packet_whitelist",
        "red_packet_blacklist",
        "red_packet_delay_custom",
        "red_packet_delay_random_range",
        "red_packet_auto_reply"
    )
}
