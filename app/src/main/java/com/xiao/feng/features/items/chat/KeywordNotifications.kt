package com.xiao.feng.features.items.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.WeDatabaseApi
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.TargetProcess
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.getSystemService
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Posts a local notification when an incoming message matches a keyword, mentions the user,
 * mentions everyone, or is a group announcement.
 *
 * The trigger is the host's own message insert, so no extra Dex target is needed. The feature is
 * always enabled as an object and gated by its own preference, because it must stay installed in
 * the push process where incoming messages are stored.
 */
object KeywordNotifications : ClickableFeature() {

    override val technicalId = "关键词通知"
    override val nameRes = R.string.feature_keyword_notifications_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_keyword_notifications_description

    override val alwaysEnabled = true
    override val noSwitchWidget = true

    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val TAG = "KeywordNotifications"
    private const val CHANNEL_PREFIX = "xfeng_keyword_notify_"
    private const val MAX_DEDUP_ENTRIES = 128
    private const val MAX_BODY_LENGTH = 500

    private var featureEnabled by prefOption("keyword_notify_enabled", false)
    private var keywordsRaw by prefOption("keyword_notify_keywords", "")
    private var matchExact by prefOption("keyword_notify_match_exact", false)
    private var notifyAtMe by prefOption("keyword_notify_at_me", true)
    private var notifyAtAll by prefOption("keyword_notify_at_all", true)
    private var postNotification by prefOption("keyword_notify_post_notification", true)
    private var notificationSound by prefOption("keyword_notify_sound", true)
    private var notificationVibrate by prefOption("keyword_notify_vibrate", true)
    private var showToast by prefOption("keyword_notify_toast", false)
    private var quietEnabled by prefOption("keyword_notify_quiet_enabled", false)
    private var quietStart by prefOption("keyword_notify_quiet_start", "23:00")
    private var quietEnd by prefOption("keyword_notify_quiet_end", "07:00")

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope = newScope()

    /** Guards against the host inserting the same row twice (retry / multi-storage paths). */
    private val handledMessageIds = object : LinkedHashSet<Long>() {
        override fun add(element: Long): Boolean {
            if (size >= MAX_DEDUP_ENTRIES) remove(iterator().next())
            return super.add(element)
        }
    }

    private val keywords: List<String>
        get() = keywordsRaw.split(',', '，', '\n', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            if (!featureEnabled) return@hookAfter
            val message = MessageInfo(args[0]!!)
            if (message.isSelfSender) return@hookAfter
            val messageId = message.id
            if (messageId > 0L) {
                synchronized(handledMessageIds) {
                    if (!handledMessageIds.add(messageId)) return@hookAfter
                }
            }
            val trigger = resolveTrigger(message) ?: return@hookAfter
            if (isInQuietWindow()) return@hookAfter
            deliver(message, trigger)
        }
    }

    override fun onDisable() {
        scope.cancel()
        synchronized(handledMessageIds) { handledMessageIds.clear() }
    }

    private fun resolveTrigger(message: MessageInfo): String? {
        val content = matchContent(message)
        if (content.isNotBlank()) {
            val exact = matchExact
            keywords.firstOrNull { keyword ->
                if (exact) content.equals(keyword, ignoreCase = true)
                else content.contains(keyword, ignoreCase = true)
            }?.let { return it }
        }
        if (message.isInGroupChat) {
            if (notifyAtMe && message.isAtMe) return "@我"
            if (notifyAtAll && (message.isNotifyAll || message.isAnnounceAll)) return "@所有人"
        }
        return null
    }

    private fun matchContent(message: MessageInfo): String =
        if (message.type?.isText == true) message.actualContent else ""

    private fun isInQuietWindow(): Boolean {
        if (!quietEnabled) return false
        val start = parseClock(quietStart) ?: return false
        val end = parseClock(quietEnd) ?: return false
        if (start == end) return true
        val calendar = Calendar.getInstance()
        val now = calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 +
            calendar.get(Calendar.SECOND)
        return if (start < end) now in start until end else now >= start || now < end
    }

    private fun parseClock(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 3600 + minute * 60
    }

    private fun deliver(message: MessageInfo, trigger: String) {
        val talker = message.talker
        if (talker.isBlank()) return
        scope.launch {
            val group = talker.isGroupChatWxId
            val sender = message.sender
            val conversationName = runCatching { WeDatabaseApi.getDisplayName(talker) }
                .getOrDefault("")
                .ifBlank { talker }
            val senderName = if (group) {
                runCatching { WeDatabaseApi.getGroupMemberDisplayName(talker, sender) }
                    .getOrDefault("")
                    .ifBlank { sender }
            } else {
                conversationName
            }
            val body = buildBody(message, trigger)
            mainHandler.post {
                if (postNotification) {
                    runCatching { showNotification(talker, conversationName, senderName, group, body) }
                        .onFailure { WeLogger.w(TAG, "failed to post keyword notification", it) }
                }
                if (showToast) {
                    runCatching { showToast("$senderName: $body") }
                        .onFailure { WeLogger.w(TAG, "failed to show keyword toast", it) }
                }
            }
        }
    }

    private fun buildBody(message: MessageInfo, trigger: String): String {
        val content = when {
            message.type?.isText == true -> message.actualContent
            else -> message.humanReadableRepr
        }.trim().ifBlank { message.humanReadableRepr }
        val prefix = if (trigger.startsWith("@")) trigger else "关键词：$trigger"
        val text = "$prefix · $content"
        return if (text.length > MAX_BODY_LENGTH) text.take(MAX_BODY_LENGTH) + "…" else text
    }

    private fun showNotification(
        talker: String,
        conversationName: String,
        senderName: String,
        group: Boolean,
        body: String,
    ) {
        val context = HostInfo.application
        val channelId = ensureChannel(context)
        val id = talker.hashCode() and 0xFFFFFF
        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(if (group) "$conversationName · $senderName" else conversationName)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setSubText(subTextFor(group))
            .setContentIntent(openChatIntent(context, talker, id))
        context.getSystemService<NotificationManager>().notify(id, builder.build())
    }

    private fun ensureChannel(context: Context): String {
        val channelId = CHANNEL_PREFIX + "s${if (notificationSound) 1 else 0}v${if (notificationVibrate) 1 else 0}"
        val manager = context.getSystemService<NotificationManager>()
        if (manager.getNotificationChannel(channelId) != null) return channelId

        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.feature_keyword_notifications_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        if (notificationSound) {
            channel.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        } else {
            channel.setSound(null, null)
        }
        channel.enableVibration(notificationVibrate)
        channel.vibrationPattern = if (notificationVibrate) longArrayOf(0, 250, 250, 250) else longArrayOf(0)
        manager.createNotificationChannel(channel)
        return channelId
    }

    private fun openChatIntent(context: Context, talker: String, id: Int): PendingIntent {
        val launcher = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.LauncherUI")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val chat = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.chatting.ChattingUI")
            putExtra("Chat_User", talker)
            putExtra("Chat_Mode", 1)
            putExtra("finish_direct", true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivities(
            context,
            id,
            arrayOf(launcher, chat),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var enabledInput by remember { mutableStateOf(featureEnabled) }
            var keywordsInput by remember { mutableStateOf(keywordsRaw) }
            var exactInput by remember { mutableStateOf(matchExact) }
            var atMeInput by remember { mutableStateOf(notifyAtMe) }
            var atAllInput by remember { mutableStateOf(notifyAtAll) }
            var notifyInput by remember { mutableStateOf(postNotification) }
            var soundInput by remember { mutableStateOf(notificationSound) }
            var vibrateInput by remember { mutableStateOf(notificationVibrate) }
            var toastInput by remember { mutableStateOf(showToast) }
            var quietInput by remember { mutableStateOf(quietEnabled) }
            var quietStartInput by remember { mutableStateOf(quietStart) }
            var quietEndInput by remember { mutableStateOf(quietEnd) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_keyword_notifications_name)) },
                text = {
                    SegmentedColumn(
                        contentPadding = PaddingValues(0.dp),
                        title = stringResource(R.string.chat_keyword_notify_section_triggers),
                    ) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_enable_title),
                                checked = enabledInput,
                                onCheckedChange = {
                                    enabledInput = it
                                    featureEnabled = it
                                },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.chat_keyword_notify_keywords_title),
                                value = keywordsInput,
                                onValueChange = {
                                    keywordsInput = it
                                    keywordsRaw = it
                                },
                                dialogTitle = stringResource(R.string.chat_keyword_notify_keywords_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.chat_keyword_notify_keywords_hint),
                                enabled = enabledInput,
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_exact_title),
                                description = stringResource(R.string.chat_keyword_notify_exact_description),
                                checked = exactInput,
                                enabled = enabledInput,
                                onCheckedChange = {
                                    exactInput = it
                                    matchExact = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_at_me_title),
                                checked = atMeInput,
                                enabled = enabledInput,
                                onCheckedChange = {
                                    atMeInput = it
                                    notifyAtMe = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_at_all_title),
                                checked = atAllInput,
                                enabled = enabledInput,
                                onCheckedChange = {
                                    atAllInput = it
                                    notifyAtAll = it
                                },
                            )
                        }
                    }
                    SegmentedColumn(
                        contentPadding = PaddingValues(0.dp),
                        title = stringResource(R.string.chat_keyword_notify_section_reminders),
                    ) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_notify_title),
                                checked = notifyInput,
                                enabled = enabledInput,
                                onCheckedChange = {
                                    notifyInput = it
                                    postNotification = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_sound_title),
                                checked = soundInput,
                                enabled = enabledInput && notifyInput,
                                onCheckedChange = {
                                    soundInput = it
                                    notificationSound = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_vibrate_title),
                                checked = vibrateInput,
                                enabled = enabledInput && notifyInput,
                                onCheckedChange = {
                                    vibrateInput = it
                                    notificationVibrate = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_toast_title),
                                checked = toastInput,
                                enabled = enabledInput,
                                onCheckedChange = {
                                    toastInput = it
                                    showToast = it
                                },
                            )
                        }
                    }
                    SegmentedColumn(
                        contentPadding = PaddingValues(0.dp),
                        title = stringResource(R.string.chat_keyword_notify_section_quiet),
                    ) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_keyword_notify_quiet_title),
                                description = stringResource(R.string.chat_keyword_notify_quiet_description),
                                checked = quietInput,
                                enabled = enabledInput,
                                onCheckedChange = {
                                    quietInput = it
                                    quietEnabled = it
                                },
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.chat_keyword_notify_quiet_start_title),
                                value = quietStartInput,
                                onValueChange = {
                                    quietStartInput = it
                                    quietStart = it
                                },
                                dialogTitle = stringResource(R.string.chat_keyword_notify_quiet_start_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = "HH:mm",
                                enabled = enabledInput && quietInput,
                            )
                        }
                        item {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.chat_keyword_notify_quiet_end_title),
                                value = quietEndInput,
                                onValueChange = {
                                    quietEndInput = it
                                    quietEnd = it
                                },
                                dialogTitle = stringResource(R.string.chat_keyword_notify_quiet_end_title),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = "HH:mm",
                                enabled = enabledInput && quietInput,
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun subTextFor(group: Boolean): String = HostInfo.application.getString(
        if (group) R.string.chat_keyword_notify_subtext_group
        else R.string.chat_keyword_notify_subtext_private
    )
}
