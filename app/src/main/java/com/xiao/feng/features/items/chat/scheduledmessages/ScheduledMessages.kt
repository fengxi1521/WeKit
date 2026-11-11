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
import com.xiao.feng.features.api.ui.WeMomentsApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.R
import com.xiao.feng.utils.AudioUtils
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.TargetProcesses
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getSystemService
import com.xiao.feng.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Calendar
import kotlin.random.Random

private const val TAG = "ScheduledMessages"

/** One-shot entries may still be delivered this long after their fire time when sendOnTimeout is on. */
private const val SEND_TIMEOUT_GRACE_MS = 10L * 60L * 1000L

/** `Calendar.SUNDAY..Calendar.SATURDAY` — the default (and maximal) [ScheduledMessage.daysOfWeek]. */
val ALL_SCHEDULED_MESSAGE_DAYS: Set<Int> = (Calendar.SUNDAY..Calendar.SATURDAY).toSet()

/** One chat a [ScheduledMessage] is delivered to. */
@Serializable
data class ScheduledMessageTarget(
    val talker: String,
    /** Display-only snapshot captured when the target was picked. */
    val displayName: String = "",
)

/** Where a [ScheduledMessage] delivers its content. */
@Serializable
enum class ScheduledMessageTargetType { CHAT, MOMENTS }

/** One content item of a [ScheduledMessage]; [value] is text, XML, or a local media path. */
@Serializable
data class ScheduledMessageContent(
    val type: ScheduledMessageContentType = ScheduledMessageContentType.TEXT,
    val value: String = "",
)

@Serializable
enum class ScheduledMessageContentType { TEXT, XML, IMAGE, VIDEO, FILE, EMOJI, VOICE }

/**
 * One user-defined alarm that sends [items] to every one of [targets] when it fires, or publishes
 * them to Moments when [targetType] is [ScheduledMessageTargetType.MOMENTS].
 *
 * [randomizeLines] treats each non-blank line of a text item as one candidate and sends a randomly
 * picked line per delivery, so a single entry can rotate several canned messages.
 */
@Serializable
data class ScheduledMessage(
    val id: String,
    val enabled: Boolean = true,
    val targets: List<ScheduledMessageTarget> = emptyList(),
    /** Legacy single-text payload; read only when [items] is empty. */
    val text: String = "",
    val kind: ScheduledMessageKind = ScheduledMessageKind.REPEATING,
    /** [ScheduledMessageKind.REPEATING] only: 0..1439, one alarm per entry. */
    val minutesOfDay: Set<Int> = emptySet(),
    /** [ScheduledMessageKind.REPEATING] only: `Calendar.SUNDAY`..`Calendar.SATURDAY`. */
    val daysOfWeek: Set<Int> = ALL_SCHEDULED_MESSAGE_DAYS,
    /** [ScheduledMessageKind.ONCE] only: the full date-time. Deleted after it fires. */
    val atEpochMillis: Long = 0L,
    val randomizeLines: Boolean = false,
    /** 多目标时每条消息之间的间隔秒数; 0 表示一次全部发出。 */
    val intervalSeconds: Int = 0,
    /** 在 [intervalSeconds] 之上再随机叠加 0..N 秒, 让发送节奏更自然。 */
    val intervalRandomSeconds: Int = 0,
    val remark: String = "",
    val targetType: ScheduledMessageTargetType = ScheduledMessageTargetType.CHAT,
    val items: List<ScheduledMessageContent> = emptyList(),
    /** 同一目标的多个内容项之间的间隔秒数。 */
    val itemIntervalSeconds: Int = 0,
    /** 单次任务错过触发时间后, 在宽限窗口内仍补发一次。 */
    val sendOnTimeout: Boolean = false,
)

/** [items] with legacy [ScheduledMessage.text] migrated in, blank values dropped. */
fun ScheduledMessage.normalizedItems(): List<ScheduledMessageContent> {
    val source = if (items.isEmpty() && text.isNotBlank()) {
        listOf(ScheduledMessageContent(ScheduledMessageContentType.TEXT, text))
    } else {
        items
    }
    return source
        .map { ScheduledMessageContent(it.type, it.value.trim()) }
        .filter { it.value.isNotBlank() }
}

/** 朋友圈任务只允许 文字 / 1-9 张图片 / 1 个视频 的组合。 */
fun ScheduledMessage.isValidMoments(): Boolean {
    val content = normalizedItems()
    val hasText = content.any { it.type == ScheduledMessageContentType.TEXT }
    val imageCount = content.count { it.type == ScheduledMessageContentType.IMAGE }
    val videoCount = content.count { it.type == ScheduledMessageContentType.VIDEO }
    val hasOther = content.any {
        it.type != ScheduledMessageContentType.TEXT &&
                it.type != ScheduledMessageContentType.IMAGE &&
                it.type != ScheduledMessageContentType.VIDEO
    }
    if (hasOther) return false
    return when {
        videoCount > 1 -> false
        videoCount == 1 -> true
        imageCount > 9 -> false
        imageCount > 0 -> true
        else -> hasText
    }
}

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
 * path. Alarms are keyed by `id#minute`, so one entry with several send times owns several alarms.
 * What differs from the hide-contacts list is the catch-up contract: a missed 显示/隐藏 flip can
 * safely be applied late, but a missed message is normally dropped. Entries that opted into
 * [ScheduledMessage.sendOnTimeout] are the exception and are delivered once inside a short grace
 * window.
 *
 * Alarms only fire while WeChat's main process exists (the module cannot add a `<receiver>` to
 * WeChat's manifest, so the receiver is registered at runtime). A fire time missed while the process
 * was dead is dropped by design.
 */
object ScheduledMessageSchedule {

    private const val KEY_MESSAGES = "scheduled_messages"

    private const val ACTION_FIRE = "com.xiao.feng.action.SCHEDULED_MESSAGE"
    private const val EXTRA_ID = "schedule_id"
    private const val EXTRA_MINUTE = "schedule_minute"

    /** Alarm key suffix used by one-shot entries, which have no minute-of-day. */
    private const val NO_MINUTE = -1

    /** Distinct from HideContactsSchedule's `xfeng://hidecontacts/...` data URIs. */
    private const val URI_SCHEME = "xfeng"
    private const val URI_AUTHORITY = "scheduled_messages"

    private var raw by KvStore.prefOption(KEY_MESSAGES, "")

    private var installed = false
    private var receiverRegistered = false

    /** 逐条发送用的作用域; 只在已安装时存在, [uninstall] 会取消排队中的发送。 */
    private var sendScope: CoroutineScope? = null

    /**
     * Alarm key -> the epoch millis it is currently armed at. Keeping the armed time lets [resync]
     * skip unchanged alarms, so saving an entry only touches Binder for real changes instead of
     * cancelling and re-arming the whole list.
     */
    private val armedTimes = mutableMapOf<String, Long>()

    /**
     * Parsed copy of [raw], reused until the stored string changes. The list is read by the settings
     * dialog and by every alarm callback, so decoding once per stored value keeps those paths free
     * of repeated JSON parsing.
     */
    @Volatile
    private var cachedRaw: String? = null

    @Volatile
    private var cachedMessages: List<ScheduledMessage> = emptyList()

    /** The schedule list, sanitized. */
    var messages: List<ScheduledMessage>
        get() = decodeRaw()
            .filter { message ->
                message.id.isNotBlank() &&
                        message.normalizedItems().isNotEmpty() &&
                        when (message.targetType) {
                            ScheduledMessageTargetType.CHAT ->
                                message.targets.isNotEmpty() &&
                                        message.targets.all { it.talker.isNotBlank() }

                            ScheduledMessageTargetType.MOMENTS -> message.isValidMoments()
                        } &&
                        isWellFormed(message)
            }
            .distinctBy { it.id }
        private set(value) {
            runCatching { raw = DefaultJson.encodeToString(value) }
                .onFailure { WeLogger.w(TAG, "failed to save scheduled messages", it) }
        }

    private fun decodeRaw(): List<ScheduledMessage> {
        val text = raw
        if (text.isEmpty()) return emptyList()
        if (text == cachedRaw) return cachedMessages
        val parsed = runCatching {
            DefaultJson.decodeFromString<List<ScheduledMessage>>(text)
        }.recoverCatching {
            DefaultJson.decodeFromString<List<LegacyScheduledMessage>>(text)
                .map { it.toScheduledMessage() }
        }.onFailure {
            WeLogger.w(TAG, "failed to parse scheduled messages, resetting", it)
        }.getOrDefault(emptyList())
        cachedRaw = text
        cachedMessages = parsed
        return parsed
    }

    private fun isWellFormed(message: ScheduledMessage): Boolean = when (message.kind) {
        ScheduledMessageKind.REPEATING -> message.daysOfWeek.isNotEmpty() &&
                message.minutesOfDay.isNotEmpty() &&
                message.minutesOfDay.all { it in 0..1439 }

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
        sendScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            registerReceiver()
            catchUp()
            resync()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to install scheduled messages, rolling back", e)
            installed = false
            cancelAll()
            unregisterReceiver()
            sendScope?.cancel()
            sendScope = null
        }
    }

    @Synchronized
    fun uninstall() {
        if (!installed) return
        installed = false
        sendScope?.cancel()
        sendScope = null
        cancelAll()
        unregisterReceiver()
    }

    /** Sends [message] right now, bypassing its schedule. Returns how many targets were accepted. */
    fun sendNow(message: ScheduledMessage): Int {
        val scope = sendScope ?: return 0
        if (message.targetType == ScheduledMessageTargetType.MOMENTS) {
            scope.launch { postToMoments(message) }
            return 1
        }
        val targets = message.targets
        val items = message.normalizedItems()
        scope.launch { sendOneByOne(message, targets, items) }
        return targets.size
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

        val desired = mutableMapOf<String, Long>()
        val now = System.currentTimeMillis()
        for (message in list) {
            if (!message.enabled) continue
            for (minute in minutesToArm(message)) {
                val next = nextFireAfter(message, minute, now)
                if (next == null) {
                    WeLogger.d(TAG, "scheduled message ${message.id}@$minute has no future fire time")
                    continue
                }
                desired[alarmKey(message.id, minute)] = next
            }
        }

        val stale = armedTimes.keys - desired.keys
        if (stale.isNotEmpty()) cancel(stale)
        for ((key, at) in desired) {
            if (armedTimes[key] == at) continue
            arm(key, at)
        }
        WeLogger.d(TAG, "resynced ${armedTimes.size} scheduled message alarm(s)")
    }

    private fun minutesToArm(message: ScheduledMessage): List<Int> = when (message.kind) {
        ScheduledMessageKind.ONCE -> listOf(NO_MINUTE)
        ScheduledMessageKind.REPEATING -> message.minutesOfDay.toList()
    }

    private fun alarmKey(id: String, minute: Int): String = "$id#$minute"

    /** Cancels exactly [keys] and forgets them. */
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
            val minute = intent.getIntExtra(EXTRA_MINUTE, NO_MINUTE)
            onFired(id, minute)
        }
    }

    @Synchronized
    private fun onFired(id: String, minute: Int) {
        val message = messages.firstOrNull { it.id == id && it.enabled } ?: return

        deliver(message)

        if (message.kind == ScheduledMessageKind.ONCE) {
            removeStored(setOf(id))
            resync()
            return
        }

        val key = alarmKey(id, minute)
        val next = nextFireAfter(message, minute, System.currentTimeMillis())
        if (next != null) arm(key, next) else armedTimes -= key
    }

    private fun deliver(message: ScheduledMessage) {
        val scope = sendScope ?: run {
            WeLogger.w(TAG, "scheduled message ${message.id} fired before the sender was ready")
            return
        }
        if (message.targetType == ScheduledMessageTargetType.MOMENTS) {
            scope.launch { postToMoments(message) }
            return
        }
        val targets = message.targets
        val items = message.normalizedItems()
        scope.launch { sendOneByOne(message, targets, items) }
    }

    /**
     * 按条目设置的间隔逐条发送, 不再把同一时刻的一串消息一次性发出去。
     * 每条之间的随机数单独取, 让发送节奏看起来更像人工。
     */
    private suspend fun sendOneByOne(
        message: ScheduledMessage,
        targets: List<ScheduledMessageTarget>,
        items: List<ScheduledMessageContent>,
    ) {
        var sent = 0
        targets.forEachIndexed { index, target ->
            if (index > 0) {
                val wait = message.nextIntervalMillis()
                if (wait > 0L) delay(wait)
            }
            val ok = runCatching { sendItems(message, target, items) }
                .onFailure { WeLogger.e(TAG, "failed to send ${message.id} to ${target.talker}", it) }
                .getOrDefault(false)
            if (ok) sent++
        }
        WeLogger.i(TAG, "scheduled message ${message.id} delivered to $sent/${targets.size} chat(s)")
    }

    /** 依次发送 [items], 单个内容失败即中止该目标的剩余内容。 */
    private suspend fun sendItems(
        message: ScheduledMessage,
        target: ScheduledMessageTarget,
        items: List<ScheduledMessageContent>,
    ): Boolean {
        val displayName = target.displayName.takeIf { it.isNotBlank() } ?: target.talker
        items.forEachIndexed { index, item ->
            if (index > 0) {
                val wait = message.itemIntervalSeconds.coerceAtLeast(0) * 1000L
                if (wait > 0L) delay(wait)
            }
            if (!sendItem(item, target.talker, displayName, message.randomizeLines)) return false
        }
        return items.isNotEmpty()
    }

    private fun sendItem(
        item: ScheduledMessageContent,
        talker: String,
        displayName: String,
        randomizeLines: Boolean,
    ): Boolean {
        val value = item.value.replace("%friendName%", displayName)
        return when (item.type) {
            ScheduledMessageContentType.TEXT -> {
                val text = if (randomizeLines) {
                    value.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .randomOrNull() ?: value
                } else {
                    value
                }
                WeMessageApi.sendText(talker, text)
            }

            ScheduledMessageContentType.XML -> WeMessageApi.sendXmlAppMsg(talker, value)
            ScheduledMessageContentType.IMAGE -> WeMessageApi.sendImage(talker, item.value)
            ScheduledMessageContentType.VIDEO -> WeMessageApi.sendVideo(talker, item.value)
            ScheduledMessageContentType.FILE ->
                WeMessageApi.sendFile(talker, item.value, File(item.value).name)

            ScheduledMessageContentType.EMOJI -> WeMessageApi.sendEmoji(talker, item.value)
            ScheduledMessageContentType.VOICE ->
                WeMessageApi.sendVoice(talker, item.value, voiceDurationMs(item.value))
        }
    }

    /** 语音时长取自模块的音频工具; 读不到时按 1 秒发送, 避免被微信拒收。 */
    private fun voiceDurationMs(path: String): Int {
        val duration = runCatching { AudioUtils.getDurationMs(path) }.getOrDefault(0L)
        return (duration.takeIf { it > 0L } ?: 1000L).coerceAtMost(60_000L).toInt()
    }

    /** 把内容按朋友圈规则发布; 图片取前 9 张, 视频取第一个。 */
    private fun postToMoments(message: ScheduledMessage): Boolean {
        val items = message.normalizedItems()
        val text = items
            .filter { it.type == ScheduledMessageContentType.TEXT }
            .joinToString("\n") { it.value }
        val images = items
            .filter { it.type == ScheduledMessageContentType.IMAGE }
            .map { it.value }
            .distinct()
            .take(9)
        val video = items.firstOrNull { it.type == ScheduledMessageContentType.VIDEO }?.value
        val posted = when {
            video != null -> {
                val thumb = WeMomentsApi.generateVideoThumbForUpload(HostInfo.application, video)
                thumb != null && WeMomentsApi.postTextAndVideo(
                    HostInfo.application,
                    text,
                    video,
                    thumb,
                )
            }

            images.isNotEmpty() -> WeMomentsApi.postTextAndImages(text, images)
            else -> WeMomentsApi.postText(text)
        }
        WeLogger.i(TAG, "scheduled moment ${message.id} posted=$posted")
        return posted
    }

    private fun ScheduledMessage.nextIntervalMillis(): Long {
        val base = intervalSeconds.coerceAtLeast(0).toLong()
        val extra = intervalRandomSeconds.coerceAtLeast(0).toLong()
        val seconds = if (extra > 0L) base + Random.nextLong(extra + 1) else base
        return seconds * 1000L
    }

    /**
     * Consumes one-shot entries whose time passed while the process was dead. Entries that opted
     * into [ScheduledMessage.sendOnTimeout] are delivered once when they are still inside the
     * grace window; every other elapsed entry is dropped (see the class KDoc). Runs at process
     * attach, before [resync], so nothing here may touch UI.
     */
    private fun catchUp() {
        val now = System.currentTimeMillis()
        val elapsed = messages.filter {
            it.kind == ScheduledMessageKind.ONCE && it.atEpochMillis <= now
        }
        val (late, dropped) = elapsed.partition {
            it.sendOnTimeout && now - it.atEpochMillis <= SEND_TIMEOUT_GRACE_MS
        }
        late.forEach(::deliver)
        removeStored(elapsed.mapTo(mutableSetOf()) { it.id })
        if (late.isNotEmpty()) {
            WeLogger.i(TAG, "late-sending ${late.size} one-shot message(s)")
        }
        if (dropped.isNotEmpty()) {
            WeLogger.i(TAG, "dropped ${dropped.size} missed one-shot message(s)")
        }
    }

    private fun nextFireAfter(message: ScheduledMessage, minute: Int, now: Long): Long? =
        when (message.kind) {
            ScheduledMessageKind.ONCE -> message.atEpochMillis.takeIf { it > now }

            ScheduledMessageKind.REPEATING -> {
                if (message.daysOfWeek.isEmpty() || minute !in 0..1439) null else {
                    (0..7).firstNotNullOfOrNull { offset ->
                        val cal = dayAt(now, minute, offset)
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

/**
 * Persisted shape from the first version of this feature (one chat, one time). Kept only so an
 * upgrade does not silently drop entries the user already created.
 */
@Serializable
private data class LegacyScheduledMessage(
    val id: String,
    val enabled: Boolean = true,
    val talker: String,
    val displayName: String = "",
    val text: String,
    val kind: ScheduledMessageKind = ScheduledMessageKind.REPEATING,
    val minuteOfDay: Int = 0,
    val daysOfWeek: Set<Int> = ALL_SCHEDULED_MESSAGE_DAYS,
    val atEpochMillis: Long = 0L,
) {
    fun toScheduledMessage(): ScheduledMessage = ScheduledMessage(
        id = id,
        enabled = enabled,
        targets = listOf(ScheduledMessageTarget(talker, displayName)),
        text = text,
        kind = kind,
        minutesOfDay = setOf(minuteOfDay),
        daysOfWeek = daysOfWeek,
        atEpochMillis = atEpochMillis,
    )
}
