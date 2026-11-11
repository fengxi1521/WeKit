package com.xiao.feng.features.items.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import com.xiao.feng.R
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseSupportingWidget
import com.xiao.feng.ui.content.m3.ColorPickerWidget
import com.xiao.feng.ui.content.m3.PlaceholderChips
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.android.isDarkMode
import com.xiao.feng.utils.android.showToast
import com.xiao.feng.utils.formatEpoch
import com.xiao.feng.utils.reflection.cachedField


/** View tags shared with [ReadReceipts] while keeping its bind state on the time view. */
const val READ_RECEIPTS_MESSAGE_ID_TAG = 0x7E000010
const val READ_RECEIPTS_BINDING_GENERATION_TAG = 0x7E000011
const val READ_RECEIPTS_COUNT_TAG = 0x7E000012
const val READ_RECEIPTS_NATIVE_TEXT_TAG = 0x7E000013

/** Avatar view of the item that owns the time view, reused when a later render has no holder. */
private const val TIME_AVATAR_TAG = 0x7E000014

/** Marks the content row whose native "below the time" rule we reversed for the below anchor. */
private const val TIME_SWAPPED_TAG = 0x7E000015

/** How the timestamp row is rendered; see `MessageTimeEnhancements.timeMode`. */
private const val TIME_MODE_ORIGINAL = "original"
private const val TIME_MODE_CUSTOM = "custom"
private const val TIME_MODE_EVERY = "every"
private const val TIME_MODE_HIDDEN = "hidden"

data class ReadReceiptCountState(val count: Int?)

object MessageTimeEnhancements : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "消息时间增强"
    override val nameRes = R.string.feature_message_time_enhancements_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_time_enhancements_description

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private var timeFormat by prefOption("msg_time_pattern", "yyyy/MM/dd HH:mm:ss")
    private var textSize by prefOption("msg_time_text_size", 11)
    private var displayFormat by prefOption("msg_time_display_format", $$"$time | $type")
    private var isAlwaysCentered by prefOption("msg_time_always_centered", false)

    /** 原样 / 自定义格式 / 每条都显示 / 隐藏, 见 [TIME_MODE_*]; 空串表示从旧版开关迁移。 */
    private var timeMode by prefOption("msg_time_mode", "")

    /** Legacy switch kept only to migrate its old value into [timeMode]. */
    private var isAlwaysVisible by prefOption("msg_time_always_visible", false)

    private val effectiveTimeMode: String
        get() = timeMode.ifEmpty {
            if (isAlwaysVisible) TIME_MODE_EVERY else TIME_MODE_CUSTOM
        }

    /** "above" keeps the timestamp above the avatar, "below" moves it under the avatar. */
    private var timeAnchor by prefOption("msg_time_anchor", "above")
    private var textColorLight by prefOption("msg_time_color_light", "gray")
    private var textColorDark by prefOption("msg_time_color_dark", "gray")

    /**
     * Color parsing runs on every message bind while the preference rarely changes, so the last
     * parsed value is kept instead of re-parsing the same string for every row.
     */
    private var parsedColorRaw: String? = null
    private var parsedColorValue: Int = Color.GRAY

    private fun parseTimeColor(raw: String): Int {
        if (raw != parsedColorRaw) {
            parsedColorRaw = raw
            parsedColorValue = runCatching { raw.toColorInt() }.getOrElse { Color.GRAY }
        }
        return parsedColorValue
    }

    private fun getFormattedText(msgInfo: MessageInfo): String {
        var result = displayFormat

        if (result.contains($$"$time")) {
            val timeStr = formatEpoch(msgInfo.createTime, timeFormat)
            result = result.replace($$"$time", timeStr)
        }

        if (result.contains($$"$relativeTime")) {
            val createTime = msgInfo.createTime
            val zoneId = java.time.ZoneId.systemDefault()
            val epochDay = java.time.LocalDate.now(zoneId).toEpochDay() -
                    java.time.Instant.ofEpochMilli(createTime).atZone(zoneId).toLocalDate().toEpochDay()
            val relTimeStr = when {
                epochDay > 1 -> localizedChatQuantity(
                    R.plurals.chat_message_time_days_ago,
                    epochDay.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    epochDay,
                )
                epochDay == 1L -> localizedChatString(R.string.chat_message_time_yesterday)
                else -> {
                    val diff = System.currentTimeMillis() - createTime
                    when {
                        diff <= 0 -> localizedChatString(R.string.chat_message_time_just_now)
                        else -> {
                            val mins = diff / 60000
                            val hours = diff / 3600000
                            when {
                                mins < 1 -> localizedChatString(R.string.chat_message_time_just_now)
                                hours < 1 -> localizedChatQuantity(
                                    R.plurals.chat_message_time_minutes_ago,
                                    mins.toInt(),
                                    mins,
                                )
                                else -> {
                                    val displayedHours = maxOf(hours, 1L)
                                    localizedChatQuantity(
                                        R.plurals.chat_message_time_hours_ago,
                                        displayedHours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        displayedHours,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            result = result.replace($$"$relativeTime", relTimeStr)
        }

        if (result.contains($$"$type")) {
//            val typeStr = "0x${msgInfo.typeCode.toString(16).uppercase(Locale.ROOT)}"
//            result = result.replace($$"$type", typeStr)
            result = result.replace($$"$type", msgInfo.typeCode.toString())
        }

        if (result.contains($$"$msgId")) {
            result = result.replace($$"$msgId", msgInfo.id.toString())
        }

        if (result.contains($$"$msgSvrId")) {
            result = result.replace($$"$msgSvrId", msgInfo.serverId.toString())
        }

        if (result.contains($$"$mentionedUsers")) {
            val atStr = when {
                msgInfo.mentionedUsers.isEmpty() -> ""
                msgInfo.isAnnounceAll -> localizedChatString(R.string.chat_message_time_group_announcement)
                msgInfo.isNotifyAll -> localizedChatString(R.string.chat_message_time_everyone)
                msgInfo.isAtMe -> localizedChatString(R.string.chat_message_time_me)
                else -> localizedChatQuantity(
                    R.plurals.chat_message_time_mentioned_people,
                    msgInfo.mentionedUsers.size,
                    msgInfo.mentionedUsers.size,
                )
            }
            result = result.replace($$"$mentionedUsers", atStr)
        }

        return result
    }

    /**
     * Re-renders the message-time view, optionally including a read-receipt count.
     *
     * Read-receipt updates use this entry point instead of reproducing the time formatting and
     * styling rules. A null count is meaningful: it clears an active template placeholder but
     * never adds a suffix to native text.
     */
    @SuppressLint("SetTextI18n")
    fun renderMessageTime(
        msgInfo: MessageInfo,
        time: TextView,
        forceVisible: Boolean = false,
        readReceiptCount: Int? = null,
        avatar: View? = null,
    ) {
        val mode = effectiveTimeMode
        if (mode == TIME_MODE_HIDDEN) {
            time.visibility = View.GONE
            return
        }

        val enhancementActive = isActive &&
                (mode == TIME_MODE_CUSTOM || mode == TIME_MODE_EVERY)
        // WeChat draws a translucent rounded pill behind the timestamp once a chat wallpaper is
        // set. Hiding it with alpha is not enough: the drawable also contributes a minimum size
        // and padding, so the rows that had the pill were measured differently from the rows that
        // did not, which is what made a few timestamps sit higher. Drop the drawable and reset the
        // minimum sizes so every row uses the exact same metrics.
        if (enhancementActive) {
            time.background = null
            time.minimumHeight = 0
            time.minimumWidth = 0
        }
        if (!enhancementActive && !forceVisible) return
        if (!forceVisible && mode == TIME_MODE_CUSTOM && !time.isVisible) return

        val context = time.context
        val baseText = if (enhancementActive) {
            getFormattedText(msgInfo)
        } else {
            readReceiptNativeText(
                time.text?.toString().orEmpty(),
                time.getTag(READ_RECEIPTS_NATIVE_TEXT_TAG) as? String,
            )
        }
        val localizedReadText = readReceiptCount?.let {
            localizedChatString(R.string.chat_read_receipts_count, it)
        }
        time.text = renderReadReceiptText(baseText, localizedReadText, enhancementActive)
        if (forceVisible || mode == TIME_MODE_EVERY) {
            time.visibility = View.VISIBLE
        }

        if (!enhancementActive) return

        // Dynamic text color configuration based on system theme
        val rawColor = if (context.isDarkMode) textColorDark else textColorLight
        time.setTextColor(parseTimeColor(rawColor))

        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize.toFloat())

        // 1. Convert 12dp to pixels dynamically so it matches standard screen-edge spacing
        val edgeMarginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            context.resources.displayMetrics
        ).toInt()

        // Pad only vertically and from a fixed zero base: recycled views may still carry the
        // horizontal padding of the removed pill, which is another source of drift.
        val verticalPaddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()
        time.setPadding(0, verticalPaddingPx, 0, verticalPaddingPx)

        val anchorGapPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            2f,
            context.resources.displayMetrics
        ).toInt()

        val lp = time.layoutParams as? RelativeLayout.LayoutParams
        if (lp != null) {
            // System messages are always centered, regardless of user config or sender
            if (isAlwaysCentered || msgInfo.type?.isSystem == true) {
                lp.addRule(RelativeLayout.CENTER_HORIZONTAL)
                lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                lp.marginStart = 0
                lp.marginEnd = 0
                time.gravity = Gravity.CENTER_HORIZONTAL
            } else {
                lp.removeRule(RelativeLayout.CENTER_HORIZONTAL)

                // 3. Conditional alignment based on who sent the message
                if (msgInfo.isSelfSender) {
                    // Align to the Right (End)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_START)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_END)

                    lp.marginEnd = edgeMarginPx
                    lp.marginStart = 0 // Clear opposing margin to prevent bugs on view recycling

                    time.gravity = Gravity.END
                } else {
                    // Align to the Left (Start)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_END)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_START)

                    lp.marginStart = edgeMarginPx

                    lp.marginEnd = 0
                    time.gravity = Gravity.START
                }
            }

            time.layoutParams = lp
        }

        if (msgInfo.type?.isSystem != true) {
            placeTimeSlot(time, avatar ?: time.getTag(TIME_AVATAR_TAG) as? View, anchorGapPx)
        }
    }

    /**
     * Moves the timestamp slot above or below the row that holds the avatar and bubble.
     *
     * The host keeps both the time slot and the avatar/bubble row inside one parent container, but
     * that container is a [LinearLayout] on some item types and a [RelativeLayout] on others, so
     * both cases are handled. When the anchor view cannot be located the timestamp keeps the
     * native position instead of being moved to a random place.
     */
    private fun placeTimeSlot(time: TextView, avatar: View?, gapPx: Int) {
        val parent = time.parent as? ViewGroup ?: return
        val contentRow = contentRowOf(parent, time, avatar)

        if (parent is LinearLayout) {
            if (contentRow == null) return
            val below = timeAnchor == "below"
            val timeIndex = parent.indexOfChild(time)
            val contentIndex = parent.indexOfChild(contentRow)
            val alreadyPlaced = if (below) timeIndex > contentIndex else timeIndex < contentIndex
            if (!alreadyPlaced) {
                parent.removeView(time)
                val index = parent.indexOfChild(contentRow) + if (below) 1 else 0
                parent.addView(time, index.coerceIn(0, parent.childCount))
            }
            return
        }

        if (parent is RelativeLayout) {
            val lp = time.layoutParams as? RelativeLayout.LayoutParams ?: return
            val below = timeAnchor == "below"
            val contentLp = contentRow?.layoutParams as? RelativeLayout.LayoutParams

            if (contentRow != null && contentLp != null) {
                if (time.id == View.NO_ID) time.id = View.generateViewId()
                if (contentRow.id == View.NO_ID) contentRow.id = View.generateViewId()
                if (below) {
                    // Reverse the native "content below time" chain instead of pointing both views
                    // at each other; RelativeLayout throws on a circular dependency.
                    contentRow.setTag(TIME_SWAPPED_TAG, true)
                    contentLp.removeRule(RelativeLayout.BELOW)
                    contentLp.removeRule(RelativeLayout.ABOVE)
                    contentLp.removeRule(RelativeLayout.ALIGN_TOP)
                    contentLp.removeRule(RelativeLayout.ALIGN_BOTTOM)
                    contentLp.addRule(RelativeLayout.ALIGN_PARENT_TOP)

                    lp.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    lp.removeRule(RelativeLayout.ABOVE)
                    lp.removeRule(RelativeLayout.BELOW)
                    lp.removeRule(RelativeLayout.ALIGN_TOP)
                    lp.removeRule(RelativeLayout.ALIGN_BOTTOM)
                    lp.addRule(RelativeLayout.BELOW, contentRow.id)
                    lp.topMargin = gapPx
                    lp.bottomMargin = 0
                } else {
                    lp.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                    lp.removeRule(RelativeLayout.ABOVE)
                    lp.removeRule(RelativeLayout.BELOW)
                    lp.removeRule(RelativeLayout.ALIGN_TOP)
                    lp.removeRule(RelativeLayout.ALIGN_BOTTOM)
                    lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    lp.topMargin = gapPx
                    lp.bottomMargin = 0

                    if (contentRow.getTag(TIME_SWAPPED_TAG) == true) {
                        contentLp.removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                        contentLp.removeRule(RelativeLayout.ABOVE)
                        contentLp.removeRule(RelativeLayout.ALIGN_TOP)
                        contentLp.removeRule(RelativeLayout.ALIGN_BOTTOM)
                        contentLp.addRule(RelativeLayout.BELOW, time.id)
                        contentRow.setTag(TIME_SWAPPED_TAG, null)
                    }
                }
                contentRow.layoutParams = contentLp
            } else {
                lp.removeRule(RelativeLayout.ABOVE)
                lp.removeRule(RelativeLayout.BELOW)
                lp.removeRule(RelativeLayout.ALIGN_BOTTOM)
                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                lp.topMargin = gapPx
                lp.bottomMargin = 0
            }
            time.layoutParams = lp
        }
    }

    /** Finds the sibling that contains the avatar, which is the row the timestamp belongs to. */
    private fun contentRowOf(parent: ViewGroup, time: View, avatar: View?): View? {
        if (avatar == null) return null
        var child: View = avatar
        while (child.parent !== parent) {
            child = child.parent as? View ?: return null
        }
        return child.takeIf { it !== time }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val tag = view.tag ?: return
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        val time = tag.cachedField("timeTV").get(tag) as? TextView? ?: return
        val avatar = tag.cachedField("avatarIV").get(tag) as? View
        avatar?.let { time.setTag(TIME_AVATAR_TAG, it) }

        val nextGeneration = ((time.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) as? Long) ?: 0L) + 1L
        time.setTag(READ_RECEIPTS_BINDING_GENERATION_TAG, nextGeneration)

        val trackedMessageId = time.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) as? Long
        val tracked = trackedMessageId == msgInfo.id
        if (!tracked) {
            time.setTag(READ_RECEIPTS_MESSAGE_ID_TAG, null)
            time.setTag(READ_RECEIPTS_COUNT_TAG, null)
            time.setTag(READ_RECEIPTS_NATIVE_TEXT_TAG, null)
        }
        val count = if (tracked) {
            (time.getTag(READ_RECEIPTS_COUNT_TAG) as? ReadReceiptCountState)?.count
        } else {
            null
        }
        renderMessageTime(msgInfo, time, forceVisible = tracked, readReceiptCount = count, avatar = avatar)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val localizedContext = LocalXiaoFengLocalizedContext.current
            var displayFormatInput by remember { mutableStateOf(TextFieldValue(displayFormat)) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var textSizeInputRaw by remember { mutableStateOf(textSize.toString()) }
            var isAlwaysCenteredInput by remember { mutableStateOf(isAlwaysCentered) }
            var modeInput by remember { mutableStateOf(effectiveTimeMode) }
            var anchorInput by remember { mutableStateOf(timeAnchor) }
            var textColorLightInput by remember { mutableStateOf(textColorLight) }
            var textColorDarkInput by remember { mutableStateOf(textColorDark) }
            var isFocused by remember { mutableStateOf(false) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.chat_message_time_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_mode_original),
                                    selected = modeInput == TIME_MODE_ORIGINAL,
                                    trailingDivider = true,
                                    onClick = { modeInput = TIME_MODE_ORIGINAL },
                                )
                            }
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_mode_custom),
                                    selected = modeInput == TIME_MODE_CUSTOM,
                                    trailingDivider = true,
                                    onClick = { modeInput = TIME_MODE_CUSTOM },
                                )
                            }
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_mode_every),
                                    selected = modeInput == TIME_MODE_EVERY,
                                    trailingDivider = true,
                                    onClick = { modeInput = TIME_MODE_EVERY },
                                )
                            }
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_mode_hidden),
                                    selected = modeInput == TIME_MODE_HIDDEN,
                                    onClick = { modeInput = TIME_MODE_HIDDEN },
                                )
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_message_time_display_template),
                                ) {
                                    Column {
                                        OutlinedTextField(
                                            value = displayFormatInput,
                                            onValueChange = { displayFormatInput = it },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .onFocusChanged { isFocused = it.isFocused }
                                        )

                                        Text(
                                            stringResource(R.string.chat_message_time_insert_placeholder),
                                            modifier = Modifier
                                                .padding(start = 16.dp, top = 8.dp)
                                        )

                                        val placeholders = listOf(
                                            $$"$time",
                                            $$"$relativeTime",
                                            $$"$type",
                                            $$"$msgId",
                                            $$"$msgSvrId",
                                            $$"$mentionedUsers",
                                            READ_RECEIPTS_PLACEHOLDER,
                                        )
                                        PlaceholderChips(
                                            placeholders = placeholders,
                                            value = displayFormatInput,
                                            isFieldFocused = isFocused,
                                            onValueChange = { displayFormatInput = it },
                                            modifier = Modifier
                                                .padding(horizontal = 16.dp),
                                        )
                                    }
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_message_time_format),
                                ) {
                                    OutlinedTextField(
                                        value = timeFormatInput,
                                        onValueChange = { timeFormatInput = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_message_time_font_size),
                                ) {
                                    OutlinedTextField(
                                        value = textSizeInputRaw,
                                        onValueChange = { textSizeInputRaw = it.filter { c -> c.isDigit() } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_message_time_color_light),
                                    value = textColorLightInput,
                                    onValueChange = { textColorLightInput = it },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_message_time_color_dark),
                                    value = textColorDarkInput,
                                    onValueChange = { textColorDarkInput = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_center),
                                    description = stringResource(R.string.chat_message_time_center_summary),
                                    checked = isAlwaysCenteredInput,
                                    onCheckedChange = { isAlwaysCenteredInput = it },
                                )
                            }
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_anchor_above),
                                    selected = anchorInput == "above",
                                    trailingDivider = true,
                                    onClick = { anchorInput = "above" },
                                )
                            }
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_message_time_anchor_below),
                                    selected = anchorInput == "below",
                                    onClick = { anchorInput = "below" },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val textSizeInput = textSizeInputRaw.toIntOrNull()
                        if (textSizeInput == null || textSizeInput <= 0) {
                            showToast(localizedContext.getString(R.string.chat_message_time_invalid_number))
                            return@Button
                        }

                        displayFormat = displayFormatInput.text
                        timeFormat = timeFormatInput
                        textSize = textSizeInput
                        isAlwaysCentered = isAlwaysCenteredInput
                        timeMode = modeInput
                        timeAnchor = anchorInput
                        textColorLight = textColorLightInput
                        textColorDark = textColorDarkInput
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }
}
