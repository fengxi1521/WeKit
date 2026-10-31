package com.xiao.feng.features.items.chat.scheduledmessages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.R
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getSystemService
import com.xiao.feng.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.util.Calendar

private const val TAG = "ScheduledMessages"

/** `Calendar.SUNDAY..Calendar.SATURDAY` — the default (and maximal) [ScheduledMessage.daysOfWeek]. */
val ALL_SCHEDULED_MESSAGE_DAYS: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()

/**
 * One user-defined alarm that sends [text] to the chat identified by [talker] when it fires.
 *
 * [displayName] is a display-only snapshot of the chat's name, captured when the entry was created,
 * so the list stays readable even if the target is renamed or later deleted.
 */
@Serializable
data class ScheduledMessage(
    val id: String,
    val enabled: Boolean = true,
    val talker: String,
    val displayName: String = "",
    val text: String,
    val kind: ScheduledMessageKind = ScheduledMessageKind.REPEATING,
    /** [ScheduledMessageKind.REPEATING] only: 0..1439. */
    val minuteOfDay: Int = 0,
    /** [ScheduledMessageKind.REPEATING] only: `Calendar.SUNDAY`..`Calendar.SATURDAY`. */
    val daysOfWeek: Set<Int> = ALL_SCHEDULED_MESSAGE_DAYS,
    /** [ScheduledMessageKind.ONCE] only: the full date-time. Deleted after it fires. */
    val atEpochMillis: Long = 0L,
)

@Serializable
enum class ScheduledMessageKind { REPEATING, ONCE }

/** Same id scheme as the other hand-edited schedule lists (see `newHideScheduleId`). */
fun newScheduledMessageId(): String = "schedmsg_${System.currentTimeMillis()}"

/**
 * The 定时消息 feature: an always-enabled, action-only row that opens the schedule editor. A fresh
 * install starts with an empty list, so [onEnable]'s scheduler install costs nothing until the user
 * adds an entry.
 */
object ScheduledMessages : ClickableFeature() {

    override val technicalId = "定时消息"
    override val nameRes = R.string.feature_scheduled_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_scheduled_messages_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    override fun onEnable() = ScheduledMessageSchedule.install()

    override fun onDisable() = ScheduledMessageSchedule.uninstall()

    override fun onClick(context: ComponentActivity) = showScheduledMessagesDialog(context)
}

/**
 * The 定时消息 scheduler.
 *
 * Same design as `HideContactsSchedule`: `AlarmManager` (not an in-process coroutine) so a fire time
 * survives Doze, with [resync] as the single reconciliation point and [mutate] as the only write
 * path. What differs is the catch-up contract: a missed 显示/隐藏 flip can safely be applied late,
 * but a missed *message* must not be sent late, so [catchUp] only consumes elapsed one-shot entries
 * and never delivers anything.
 *
 * Alarms only fire while WeChat's main process exists (the module cannot add a `<receiver>` to
 * WeChat's manifest, so the receiver is registered at runtime). A fire time missed while the process
 * was dead is dropped by design.
 */
object ScheduledMessageSchedule {

    private const val KEY_MESSAGES = "scheduled_messages"

    private const val ACTION_FIRE = "com.xiao.feng.action.SCHEDULED_MESSAGE"
    private const val EXTRA_ID = "schedule_id"

    /** Distinct from HideContactsSchedule's `xfeng://hidecontacts/...` data URIs. */
    private const val URI_SCHEME = "xfeng"
    private const val URI_AUTHORITY = "scheduled_messages"

    private var raw by KvStore.prefOption(KEY_MESSAGES, "")

    private var installed = false
    private var receiverRegistered = false
    private val armedIds = mutableSetOf<String>()

    /** The schedule list, sanitized. Reads parse from [KvStore] every time. */
    var messages: List<ScheduledMessage>
        get() = decodeRaw()
            .filter { it.id.isNotBlank() && it.talker.isNotBlank() && it.text.isNotBlank() && isWellFormed(it) }
            .distinctBy { it.id }
        private set(value) {
            runCatching { raw = DefaultJson.encodeToString(value) }
                .onFailure { WeLogger.w(TAG, "failed to save scheduled messages", it) }
        }

    private fun decodeRaw(): List<ScheduledMessage> {
        val text = raw
        if (text.isEmpty()) return emptyList()
        return runCatching { DefaultJson.decodeFromString<List<ScheduledMessage>>(text) }
            .onFailure { WeLogger.w(TAG, "failed to parse scheduled messages, resetting", it) }
            .getOrDefault(emptyList())
    }

    private fun isWellFormed(message: ScheduledMessage): Boolean = when (message.kind) {
        ScheduledMessageKind.REPEATING -> message.minuteOfDay in 0..1439
        ScheduledMessageKind.ONCE -> message.atEpochMillis > 0L
    }

    /** Removes exactly [ids] from the *stored* list, preserving entries the getter filters out. */
    private fun removeStored(ids: Set<String>) {
        if (ids.isEmpty()) return
        messages = decodeRaw().filterNot { it.id in ids }
    }

    /**
     * The settings UI's only write path: applies [transform] to the current list, persists it and
     * re-arms every alarm as one guarded step. Shares the monitor with [onFired] and [catchUp] so a
     * fire cannot land between the read and the write (see `HideContactsSchedule.mutate`).
     */
    @Synchronized
    fun mutate(transform: (List<ScheduledMessage>) -> List<ScheduledMessage>): List<ScheduledMessage> {
        messages = transform(messages)
        resync()
        return messages
    }

    @Synchronized
    fun install() {
        if (!TargetProcesses.isInMain) return
        if (installed) return
        installed = true
        try {
            registerReceiver()
            catchUp()
            resync()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to install scheduled messages, rolling back", e)
            installed = false
            cancelAll()
            unregisterReceiver()
        }
    }

    @Synchronized
    fun uninstall() {
        if (!installed) return
        installed = false
        cancelAll()
        unregisterReceiver()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_FIRE).apply { addDataScheme(URI_SCHEME) }
        ContextCompat.registerReceiver(
            HostInfo.application, AlarmReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        WeLogger.d(TAG, "registered scheduled message alarm receiver")
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(AlarmReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister alarm receiver", it) }
    }

    @Synchronized
    private fun resync() {
        if (!installed) return
        val list = messages
        cancelAll(list.map { it.id })

        val now = System.currentTimeMillis()
        for (message in list) {
            if (!message.enabled) continue
            val next = nextFireAfter(message, now)
            if (next == null) {
                WeLogger.d(TAG, "scheduled message ${message.id} has no future fire time; skipped")
                continue
            }
            arm(message.id, next)
        }
        WeLogger.d(TAG, "resynced ${armedIds.size} scheduled message alarm(s)")
    }

    private fun cancelAll(extraIds: List<String> = emptyList()) {
        val alarmManager = HostInfo.application.getSystemService<AlarmManager>()
        for (id in armedIds + extraIds) {
            val pendingIntent = pendingIntentFor(id)
            runCatching { alarmManager.cancel(pendingIntent) }
                .onFailure { WeLogger.w(TAG, "failed to cancel alarm for $id", it) }
            pendingIntent.cancel()
        }
        armedIds.clear()
    }

    private fun arm(id: String, atEpochMillis: Long) {
        val alarmManager = HostInfo.application.getSystemService<AlarmManager>()
        val pendingIntent = pendingIntentFor(id)

        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)

        val armed = canBeExact && runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent)
        }.onFailure { WeLogger.w(TAG, "exact alarm rejected for $id, falling back to inexact", it) }
            .isSuccess

        if (!armed) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent)
            }.onFailure {
                WeLogger.w(TAG, "failed to arm alarm for $id", it)
                return
            }
        }
        armedIds += id
    }

    private fun pendingIntentFor(id: String): PendingIntent {
        val intent = Intent(ACTION_FIRE).apply {
            setPackage(HostInfo.packageName)
            data = "$URI_SCHEME://$URI_AUTHORITY/schedule/$id".toUri()
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            HostInfo.application,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private object AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_FIRE) return
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            onFired(id)
        }
    }

    @Synchronized
    private fun onFired(id: String) {
        val list = messages
        val message = list.firstOrNull { it.id == id && it.enabled } ?: return

        if (!deliver(message)) {
            WeLogger.w(TAG, "scheduled message $id failed to send to ${message.talker}")
        }

        if (message.kind == ScheduledMessageKind.ONCE) {
            removeStored(setOf(id))
            armedIds -= id
            resync()
            return
        }

        val next = nextFireAfter(message, System.currentTimeMillis())
        if (next != null) arm(id, next) else armedIds -= id
    }

    private fun deliver(message: ScheduledMessage): Boolean =
        runCatching { WeMessageApi.sendText(message.talker, message.text) }
            .onFailure { WeLogger.e(TAG, "failed to send scheduled message ${message.id}", it) }
            .getOrDefault(false)
            .also { WeLogger.i(TAG, "scheduled message ${message.id} fired (sent=$it)") }

    /**
     * Consumes one-shot entries whose time passed while the process was dead, *without* sending them
     * (see the class KDoc), then leaves repeating entries to [resync]. Runs at process attach, so
     * nothing here may touch UI.
     */
    private fun catchUp() {
        val now = System.currentTimeMillis()
        val elapsedOnceIds = messages
            .filter { it.kind == ScheduledMessageKind.ONCE && it.atEpochMillis <= now }
            .mapTo(mutableSetOf()) { it.id }
        removeStored(elapsedOnceIds)
        if (elapsedOnceIds.isNotEmpty()) {
            WeLogger.i(TAG, "dropped ${elapsedOnceIds.size} missed one-shot message(s)")
        }
    }

    private fun nextFireAfter(message: ScheduledMessage, now: Long): Long? = when (message.kind) {
        ScheduledMessageKind.ONCE -> message.atEpochMillis.takeIf { it > now }

        ScheduledMessageKind.REPEATING -> {
            if (message.daysOfWeek.isEmpty()) null else {
                (0..7).firstNotNullOfOrNull { offset ->
                    val cal = dayAt(now, message.minuteOfDay, offset)
                    cal.takeIf {
                        it.timeInMillis > now && it.get(Calendar.DAY_OF_WEEK) in message.daysOfWeek
                    }?.timeInMillis
                }
            }
        }
    }

    private fun dayAt(now: Long, minuteOfDay: Int, dayOffset: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (dayOffset != 0) add(Calendar.DAY_OF_MONTH, dayOffset)
        }
}
