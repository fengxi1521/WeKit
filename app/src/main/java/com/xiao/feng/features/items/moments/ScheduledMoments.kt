package com.xiao.feng.features.items.moments

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
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getSystemService
import com.xiao.feng.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.util.Calendar

private const val TAG = "ScheduledMoments"

/** `Calendar.SUNDAY..Calendar.SATURDAY` — the default (and maximal) [ScheduledMoment.daysOfWeek]. */
val ALL_SCHEDULED_MOMENT_DAYS: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()

/**
 * One user-defined alarm that publishes [text] to Moments.
 *
 * [randomizeLines] treats each non-blank line of [text] as one candidate and picks a random line
 * per post, so a single entry can rotate several canned posts.
 */
@Serializable
data class ScheduledMoment(
    val id: String,
    val enabled: Boolean = true,
    val text: String,
    /** 0..1439, one alarm per entry. */
    val minutesOfDay: Set<Int> = emptySet(),
    /** `Calendar.SUNDAY`..`Calendar.SATURDAY`. */
    val daysOfWeek: Set<Int> = ALL_SCHEDULED_MOMENT_DAYS,
    val randomizeLines: Boolean = false,
)

fun newScheduledMomentId(): String = "schedmoment_${System.currentTimeMillis()}"

object ScheduledMoments : ClickableFeature() {

    override val technicalId = "定时朋友圈"
    override val nameRes = R.string.feature_scheduled_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_scheduled_moments_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    override fun onEnable() = ScheduledMomentSchedule.install()

    override fun onDisable() = ScheduledMomentSchedule.uninstall()

    override fun onClick(context: ComponentActivity) = showScheduledMomentsDialog(context)
}

/**
 * The 定时朋友圈 scheduler. Same `AlarmManager` design as `ScheduledMessageSchedule`: alarms survive
 * Doze, [resync] is the single reconciliation point and [mutate] the only write path. A fire time
 * missed while WeChat's main process was dead is dropped instead of posted late.
 */
object ScheduledMomentSchedule {

    private const val KEY_MOMENTS = "scheduled_moments"

    private const val ACTION_FIRE = "com.xiao.feng.action.SCHEDULED_MOMENT"
    private const val EXTRA_ID = "schedule_id"
    private const val EXTRA_MINUTE = "schedule_minute"

    private const val URI_SCHEME = "xfeng"
    private const val URI_AUTHORITY = "scheduled_moments"

    private var raw by KvStore.prefOption(KEY_MOMENTS, "")

    private var installed = false
    private var receiverRegistered = false

    private val armedTimes = mutableMapOf<String, Long>()

    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedMoments: List<ScheduledMoment> = emptyList()

    var moments: List<ScheduledMoment>
        get() = decodeRaw()
            .filter { moment ->
                moment.id.isNotBlank() &&
                        moment.text.isNotBlank() &&
                        moment.daysOfWeek.isNotEmpty() &&
                        moment.minutesOfDay.isNotEmpty() &&
                        moment.minutesOfDay.all { it in 0..1439 }
            }
            .distinctBy { it.id }
        private set(value) {
            runCatching { raw = DefaultJson.encodeToString(value) }
                .onFailure { WeLogger.w(TAG, "failed to save scheduled moments", it) }
        }

    private fun decodeRaw(): List<ScheduledMoment> {
        val text = raw
        if (text.isEmpty()) return emptyList()
        if (text == cachedRaw) return cachedMoments
        val parsed = runCatching { DefaultJson.decodeFromString<List<ScheduledMoment>>(text) }
            .onFailure { WeLogger.w(TAG, "failed to parse scheduled moments, resetting", it) }
            .getOrDefault(emptyList())
        cachedRaw = text
        cachedMoments = parsed
        return parsed
    }

    @Synchronized
    fun mutate(transform: (List<ScheduledMoment>) -> List<ScheduledMoment>): List<ScheduledMoment> {
        moments = transform(moments)
        resync()
        return moments
    }

    @Synchronized
    fun install() {
        if (!TargetProcesses.isInMain) return
        if (installed) return
        installed = true
        try {
            registerReceiver()
            resync()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to install scheduled moments, rolling back", e)
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

    /** Posts [moment] right now, bypassing its schedule. */
    fun postNow(moment: ScheduledMoment): Boolean {
        val text = moment.textToPost()
        val posted = runCatching { WeMomentsApi.postText(text) }.getOrDefault(false)
        WeLogger.i(TAG, "manual post of ${moment.id} succeeded=$posted")
        return posted
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_FIRE).apply { addDataScheme(URI_SCHEME) }
        ContextCompat.registerReceiver(
            HostInfo.application, AlarmReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(AlarmReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister scheduled moment receiver", it) }
    }

    @Synchronized
    private fun resync() {
        if (!installed) return
        val list = moments

        val desired = mutableMapOf<String, Long>()
        val now = System.currentTimeMillis()
        for (moment in list) {
            if (!moment.enabled) continue
            for (minute in moment.minutesOfDay) {
                val next = nextFireAfter(moment, minute, now)
                if (next == null) {
                    WeLogger.d(TAG, "scheduled moment ${moment.id}@$minute has no future fire time")
                    continue
                }
                desired[alarmKey(moment.id, minute)] = next
            }
        }

        val stale = armedTimes.keys - desired.keys
        if (stale.isNotEmpty()) cancel(stale)
        for ((key, at) in desired) {
            if (armedTimes[key] == at) continue
            arm(key, at)
        }
        WeLogger.d(TAG, "resynced ${armedTimes.size} scheduled moment alarm(s)")
    }

    private fun alarmKey(id: String, minute: Int): String = "$id#$minute"

    private fun cancel(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val alarmManager = HostInfo.application.getSystemService<AlarmManager>()
        for (key in keys.toList()) {
            val pendingIntent = pendingIntentFor(key)
            runCatching { alarmManager.cancel(pendingIntent) }
                .onFailure { WeLogger.w(TAG, "failed to cancel alarm for $key", it) }
            pendingIntent.cancel()
            armedTimes -= key
        }
    }

    private fun cancelAll() = cancel(armedTimes.keys)

    private fun arm(key: String, atEpochMillis: Long) {
        val alarmManager = HostInfo.application.getSystemService<AlarmManager>()
        val pendingIntent = pendingIntentFor(key)

        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)

        val armed = canBeExact && runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent)
        }.onFailure { WeLogger.w(TAG, "exact alarm rejected for $key, falling back to inexact", it) }
            .isSuccess

        if (!armed) {
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, pendingIntent)
            }.onFailure {
                WeLogger.w(TAG, "failed to arm alarm for $key", it)
                armedTimes -= key
                return
            }
        }
        armedTimes[key] = atEpochMillis
    }

    private fun pendingIntentFor(key: String): PendingIntent {
        val (id, minuteText) = key.split('#', limit = 2).let { it[0] to it[1] }
        val minute = minuteText.toInt()
        val intent = Intent(ACTION_FIRE).apply {
            setPackage(HostInfo.packageName)
            data = "$URI_SCHEME://$URI_AUTHORITY/schedule/$id/$minute".toUri()
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_MINUTE, minute)
        }
        return PendingIntent.getBroadcast(
            HostInfo.application,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private object AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_FIRE) return
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val minute = intent.getIntExtra(EXTRA_MINUTE, 0)
            onFired(id, minute)
        }
    }

    @Synchronized
    private fun onFired(id: String, minute: Int) {
        val moment = moments.firstOrNull { it.id == id && it.enabled } ?: return

        val text = moment.textToPost()
        val posted = runCatching { WeMomentsApi.postText(text) }
            .onFailure { WeLogger.e(TAG, "failed to post scheduled moment $id", it) }
            .getOrDefault(false)
        WeLogger.i(TAG, "scheduled moment $id fired (posted=$posted)")

        val key = alarmKey(id, minute)
        val next = nextFireAfter(moment, minute, System.currentTimeMillis())
        if (next != null) arm(key, next) else armedTimes -= key
    }

    private fun ScheduledMoment.textToPost(): String {
        if (!randomizeLines) return text.trim()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.randomOrNull() ?: text.trim()
    }

    private fun nextFireAfter(moment: ScheduledMoment, minute: Int, now: Long): Long? {
        if (moment.daysOfWeek.isEmpty() || minute !in 0..1439) return null
        return (0..7).firstNotNullOfOrNull { offset ->
            val cal = dayAt(now, minute, offset)
            cal.takeIf {
                it.timeInMillis > now && it.get(Calendar.DAY_OF_WEEK) in moment.daysOfWeek
            }?.timeInMillis
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
