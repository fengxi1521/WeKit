package com.xiao.feng.features.items.chat

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeDatabaseListenerApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Blocks messages from selected members in selected groups by dropping them before SQLite sees
 * them. Because the row never exists, the chat list, notifications, summaries and multi-device
 * sync all stay clean, and the sender has no way to notice. Outgoing messages always pass.
 */
object GroupMemberBlock : ClickableFeature(), WeDatabaseListenerApi.IInsertFilter {

    private const val TAG = "GroupMemberBlock"
    private const val KEY_RULES = "group_member_block_rules"
    private const val KEY_LOG = "group_member_block_log"
    private const val MAX_LOG = 200
    private const val MODE_HIDE = 0
    private const val MODE_SILENT = 1

    override val technicalId = "群内屏蔽指定成员"
    override val nameRes = R.string.feature_group_member_block_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_member_block_description

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class GroupRule(
        val g: String,
        val u: List<String> = emptyList(),
        val m: Int = MODE_SILENT,
    )

    @Serializable
    private data class BlockLog(
        val t: Long,
        val g: String,
        val gn: String,
        val s: String,
        val sn: String,
        val ty: Int,
    )

    @Volatile
    private var rules: List<GroupRule> = emptyList()

    override fun onEnable() {
        rules = loadRules()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        rules = emptyList()
    }

    override fun allowInsert(table: String, values: ContentValues): Boolean {
        if (table != "message") return true
        val currentRules = rules
        if (currentRules.isEmpty()) return true

        val msg = MessageInfo.fromContentValues(values)
        if (msg.isSelfSender) return true

        val talker = msg.talker
        if (talker.isEmpty()) return true
        val rule = currentRules.firstOrNull { it.g == talker } ?: return true

        val sender = msg.sender
        if (sender.isEmpty() || sender !in rule.u) return true

        if (rule.m == MODE_SILENT) {
            appendLog(
                BlockLog(
                    t = System.currentTimeMillis(),
                    g = talker,
                    gn = runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(""),
                    s = sender,
                    sn = runCatching { WeDatabaseApi.getGroupMemberDisplayName(talker, sender) }
                        .getOrDefault(sender),
                    ty = msg.typeCode,
                )
            )
        }

        WeLogger.i(TAG, "blocked group member message: group=$talker sender=$sender mode=${rule.m}")
        return false
    }

    override fun onClick(context: ComponentActivity) {
        showMainDialog(context)
    }

    private fun loadRules(): List<GroupRule> = runCatching {
        json.decodeFromString<List<GroupRule>>(KvStore.getStringOrDef(KEY_RULES, "[]"))
    }.getOrElse {
        WeLogger.w(TAG, "failed to parse block rules", it)
        emptyList()
    }

    private fun saveRules(newRules: List<GroupRule>) {
        rules = newRules
        runCatching { KvStore.putString(KEY_RULES, json.encodeToString(newRules)) }
            .onFailure { WeLogger.e(TAG, "failed to save block rules", it) }
    }

    private fun loadLogs(): List<BlockLog> = runCatching {
        json.decodeFromString<List<BlockLog>>(KvStore.getStringOrDef(KEY_LOG, "[]"))
    }.getOrElse { emptyList() }

    private fun appendLog(log: BlockLog) {
        runCatching {
            val logs = loadLogs().toMutableList()
            logs.add(log)
            KvStore.putString(KEY_LOG, json.encodeToString(logs.takeLast(MAX_LOG)))
        }.onFailure { WeLogger.w(TAG, "failed to record block log", it) }
    }

    private fun showMainDialog(ctx: Context) {
        val currentRules = loadRules()
        val titles = currentRules.map { rule ->
            HostLocalizedStrings.get(
                R.string.group_member_block_rule_summary,
                groupLabel(rule.g),
                rule.u.size,
                modeLabel(rule.m),
            )
        }
        val actions = titles + listOf(
            HostLocalizedStrings.get(R.string.group_member_block_add_rule),
            HostLocalizedStrings.get(R.string.group_member_block_logs, loadLogs().size),
            HostLocalizedStrings.get(R.string.group_member_block_export),
            HostLocalizedStrings.get(R.string.group_member_block_import),
        )

        AlertDialog.Builder(ctx)
            .setTitle(HostLocalizedStrings.get(R.string.group_member_block_title))
            .setItems(actions.toTypedArray()) { _, which ->
                when {
                    which < titles.size -> showRuleActions(ctx, currentRules[which])
                    which == titles.size -> pickGroup(ctx)
                    which == titles.size + 1 -> showLogs(ctx)
                    which == titles.size + 2 -> exportRules(ctx)
                    else -> importRules(ctx)
                }
            }
            .setNegativeButton(HostLocalizedStrings.get(R.string.group_member_block_close), null)
            .show()
    }

    private fun showRuleActions(ctx: Context, rule: GroupRule) {
        val items = arrayOf(
            HostLocalizedStrings.get(R.string.group_member_block_edit_members, rule.u.size),
            HostLocalizedStrings.get(R.string.group_member_block_switch_mode, modeLabel(rule.m)),
            HostLocalizedStrings.get(R.string.group_member_block_delete_rule),
        )
        AlertDialog.Builder(ctx)
            .setTitle(groupLabel(rule.g))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickMembers(ctx, rule)
                    1 -> {
                        val currentRules = loadRules()
                        val index = currentRules.indexOfFirst { it.g == rule.g }
                        if (index >= 0) {
                            val updated = rule.copy(
                                m = if (rule.m == MODE_HIDE) MODE_SILENT else MODE_HIDE
                            )
                            saveRules(currentRules.toMutableList().also { it[index] = updated })
                            WeLogger.i(TAG, "mode switched to ${updated.m}")
                        }
                        showMainDialog(ctx)
                    }

                    else -> {
                        saveRules(loadRules().filterNot { it.g == rule.g })
                        showMainDialog(ctx)
                    }
                }
            }
            .setNegativeButton(HostLocalizedStrings.get(R.string.group_member_block_back), null)
            .show()
    }

    private fun pickGroup(ctx: Context) {
        val groups = runCatching { WeDatabaseApi.getGroups() }.getOrDefault(emptyList())
        if (groups.isEmpty()) {
            WeLogger.w(TAG, "group list is empty; database may not be ready")
            return
        }
        val names = groups.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(HostLocalizedStrings.get(R.string.group_member_block_pick_group))
            .setItems(names) { _, which ->
                val group = groups[which]
                val existing = loadRules().firstOrNull { it.g == group.wxId }
                pickMembers(ctx, existing ?: GroupRule(g = group.wxId))
            }
            .setNegativeButton(HostLocalizedStrings.get(R.string.quick_hide_cancel), null)
            .show()
    }

    private fun pickMembers(ctx: Context, rule: GroupRule) {
        val members = runCatching { WeDatabaseApi.getGroupMembers(rule.g) }.getOrDefault(emptyList())
        if (members.isEmpty()) {
            WeLogger.w(TAG, "member list is empty for ${rule.g}")
            return
        }
        val labels = members.map { it.displayName }.toTypedArray()
        val checked = BooleanArray(members.size) { members[it].wxId in rule.u }

        AlertDialog.Builder(ctx)
            .setTitle(HostLocalizedStrings.get(R.string.group_member_block_pick_members))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(HostLocalizedStrings.get(R.string.quick_hide_now)) { _, _ ->
                val picked = members.filterIndexed { index, _ -> checked[index] }.map { it.wxId }
                val currentRules = loadRules()
                val index = currentRules.indexOfFirst { it.g == rule.g }
                val updated = rule.copy(u = picked)
                if (index >= 0) currentRules.toMutableList().also { it[index] = updated }
                    .let(::saveRules)
                else saveRules(currentRules + updated)
                WeLogger.i(TAG, "saved ${picked.size} blocked members for ${rule.g}")
                showMainDialog(ctx)
            }
            .setNegativeButton(HostLocalizedStrings.get(R.string.quick_hide_cancel), null)
            .show()
    }

    private fun showLogs(ctx: Context) {
        val logs = loadLogs()
        if (logs.isEmpty()) {
            WeLogger.i(TAG, "no block logs yet")
            return
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val text = logs.reversed().joinToString("\n") { log ->
            HostLocalizedStrings.get(
                R.string.group_member_block_log_entry,
                formatter.format(Date(log.t)),
                log.gn.ifBlank { log.g },
                log.sn,
                log.ty,
            )
        }
        AlertDialog.Builder(ctx)
            .setTitle(HostLocalizedStrings.get(R.string.group_member_block_log_title, logs.size))
            .setMessage(text)
            .setPositiveButton(HostLocalizedStrings.get(R.string.group_member_block_close), null)
            .setNeutralButton(HostLocalizedStrings.get(R.string.group_member_block_log_copy)) { _, _ ->
                copyToClipboard(ctx, text)
            }
            .show()
    }

    private fun exportRules(ctx: Context) {
        copyToClipboard(ctx, json.encodeToString(loadRules()))
        WeLogger.i(TAG, "exported ${loadRules().size} rules to clipboard")
    }

    private fun importRules(ctx: Context) {
        val text = primaryClipboardText(ctx)
        if (text.isNullOrBlank()) {
            WeLogger.w(TAG, "clipboard is empty")
            return
        }
        val parsed = runCatching { json.decodeFromString<List<GroupRule>>(text) }.getOrElse { error ->
            WeLogger.w(TAG, "failed to parse imported rules", error)
            return
        }
        if (parsed.isEmpty()) return

        val merged = loadRules().associateBy { it.g }.toMutableMap()
        parsed.forEach { merged[it.g] = it }
        saveRules(merged.values.toList())
        WeLogger.i(TAG, "imported ${parsed.size} group rules")
        showMainDialog(ctx)
    }

    private fun groupLabel(groupId: String): String =
        runCatching { WeDatabaseApi.getDisplayName(groupId) }.getOrDefault("").ifBlank { groupId }

    private fun modeLabel(mode: Int): String = HostLocalizedStrings.get(
        if (mode == MODE_HIDE) R.string.group_member_block_mode_hide
        else R.string.group_member_block_mode_silent
    )

    private fun copyToClipboard(ctx: Context, text: String) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("XiaoFeng group member block", text))
    }

    private fun primaryClipboardText(ctx: Context): String? {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }
}
