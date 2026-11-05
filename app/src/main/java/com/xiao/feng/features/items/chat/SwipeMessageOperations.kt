package com.xiao.feng.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.data.KvStore
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexClass
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.WeServiceApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.EditIcon
import com.xiao.feng.ui.utils.ExposurePlus1Icon
import com.xiao.feng.ui.utils.ReplyIcon
import com.xiao.feng.ui.utils.UndoIcon
import com.xiao.feng.ui.utils.dpToPx
import com.xiao.feng.utils.reflection.cachedField
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.android.isDarkMode
import com.xiao.feng.utils.android.showToastSuspend
import com.xiao.feng.utils.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object SwipeMessageOperations : ClickableFeature(), IResolveDex,
    WeChatMessageViewApi.ICreateViewListener {

    private const val PREF_ENABLE_SECONDARY = "swipe_to_quote_or_repeat_right_repeat"
    private const val PREF_SWAP_DIRECTIONS = "swipe_to_quote_or_repeat_swap_dir"
    private const val PREF_USE_EDIT = "swipe_secondary_action_edit"
    private const val PREF_USE_REVOKE = "swipe_secondary_action_revoke"

    override val technicalId = "滑动消息快捷操作"
    override val nameRes = R.string.feature_swipe_message_operations_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_swipe_message_operations_description

    // Mutable per-view gesture state. RecyclerView recycles message views, so chattingContext is
    // refreshed on every onBindView (see onCreateView) rather than captured once.
    private class SwipeState(
        val touchSlop: Int,
        val triggerThreshold: Float,
        // A revoke swipe dragged past this second threshold additionally reopens the message for
        // editing; the first threshold only revokes.
        val longTriggerThreshold: Float,
        // Weak: the map below is keyed by the row view, and the chatting context strongly reaches
        // the whole activity. Holding it strongly would keep every swiped chat page alive.
        var chattingContext: WeakReference<Any>? = null,
        var msgInfo: MessageInfo? = null,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var dragDirection: DragDirection? = null,
        var triggered: Boolean = false,
        var longTriggered: Boolean = false,
        var actionOverlay: SwipeActionOverlay? = null,
        // Preferences are snapshotted once per gesture: reading them per frame would hit the
        // preference database on every MOVE event.
        var secondaryEnabled: Boolean = false,
        var secondarySwapped: Boolean = false,
        var secondaryAction: SecondaryAction = SecondaryAction.REPEAT,
    )

    private enum class DragDirection { LEFT, RIGHT }

    // The action bound to the secondary swipe direction. Selection is exclusive: the prefs below
    // are kept in sync so only one of them is true at any time.
    private enum class SecondaryAction { REPEAT, EDIT, REVOKE }

    // WeakHashMap: entries are removed automatically once the recycled row view is GC'd.
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    private val springInterpolator = OvershootInterpolator(1.3f)

    private var enableSecondary by prefOption(PREF_ENABLE_SECONDARY, false)
    private var swapDirections by prefOption(PREF_SWAP_DIRECTIONS, false)

    // When true, the secondary (non-quote) swipe action is "edit" instead of "repeat".
    private var useEditInsteadOfRepeat by prefOption(PREF_USE_EDIT, false)

    // When true, the secondary swipe action revokes the message instead of repeating / editing.
    private var useRevokeInsteadOfRepeat by prefOption(PREF_USE_REVOKE, false)

    /**
     * Caches the resolved ChatFooter per chatting context so taps stop rebuilding the reflection
     * chain. The footer strongly reaches the activity, which in turn reaches this context, so the
     * entry is held weakly to avoid pinning the whole chat page.
     */
    private val chatFooterCache =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<ChatFooter>>())

    private val gesturePrefKeys = setOf(
        PREF_ENABLE_SECONDARY,
        PREF_SWAP_DIRECTIONS,
        PREF_USE_EDIT,
        PREF_USE_REVOKE,
    )

    // com.tencent.mm.ui.chatting.viewitems.ChattingItemContainer (obfuscated: xg / li). This
    // RelativeLayout is the SHARED root of every message item type (each ChattingItem.F() wraps its
    // layout in `new <this>(inflater, layoutRes)`), and it is exactly the itemView handed to
    // onCreateView below. The clickable / long-pressable message bubble is a CHILD of it, so the
    // container only sees MOVE events after it intercepts them — which is why we must hook
    // onInterceptTouchEvent here rather than rely on an OnTouchListener alone. Scoping the hook to
    // this one class (instead of global ViewGroup) keeps the blast radius tiny.
    private val classChattingItemContainer by dexClass()

    // ChattingUIFragment installs this listener on the chat list. On ACTION_DOWN it asks the
    // current ChatFooter to close the keyboard/panel, then returns false so normal list handling
    // continues. A blank-area DOWN claimed by our row never reaches that listener, so resolve the
    // exact ChatFooter call and mirror it at the point where we claim the stream.
    private val methodChatListOnTouch by dexMethod()

    private val methodHideChatInput by dexMethod()


    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)

        // Steal the horizontal-left drag from the child bubble before it becomes a click / long
        // press. onInterceptTouchEvent is the ONLY place the container can claim a MOVE stream that a
        // clickable child already owns; an OnTouchListener can't intercept. Once we return true here,
        // the tail (MOVE/UP/CANCEL) is delivered to the container itself and handled by the
        // OnTouchListener attached in onCreateView.
        classChattingItemContainer.reflekt()
            .firstMethod { name = "onInterceptTouchEvent" }
            .hookAfter {
                val v = thisObject as? ViewGroup ?: return@hookAfter
                val s = states[v] ?: return@hookAfter
                val event = args[0] as MotionEvent
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        s.startX = event.rawX
                        s.startY = event.rawY
                        s.dragDirection = null
                        s.triggered = false
                        s.longTriggered = false
                        s.actionOverlay?.dismiss(animated = false)
                        s.actionOverlay = null
                        snapshotGesturePrefs(s)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - s.startX
                        val dy = event.rawY - s.startY
                        if (s.dragDirection == null) {
                            detectDragDirection(dx, dy, s)?.let {
                                s.dragDirection = it
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                        }
                        if (s.dragDirection != null) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            result = true
                        }
                    }
                }
            }
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        states.clear()
    }

    // ── row binding: register state + attach the swipe listener, keep context fresh ─

    override fun onCreateView(param: HookParam, view: View) {
        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)

        val state = states.getOrPut(view) {
            val ctx = view.context
            val threshold = 60.dpToPx(ctx).toFloat()
            SwipeState(
                touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop,
                triggerThreshold = threshold,
                longTriggerThreshold = threshold * 2,
            )
        }
        state.chattingContext = WeakReference(chattingContext)
        state.msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        // Re-install our wrapper every bind, delegating to whatever listener is currently attached
        // (unless it is already ours), mirroring SwipeToDeleteConversation.
        attachSwipeListener(view, state)
    }

    // Marks our wrapper so a re-bind can tell its own listener apart from WeChat's.
    private class SwipeTouchListener(
        val state: SwipeState,
        val delegate: View.OnTouchListener?,
    ) : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val consumed = handleSwipe(v, state, event)
            runCatching { delegate?.onTouch(v, event) }
            return consumed
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeListener(view: View, state: SwipeState) {
        val current = getAttachedTouchListener(view)
        if (current is SwipeTouchListener) return  // already wrapped for this stream
        view.setOnTouchListener(SwipeTouchListener(state, current))
    }

    // Reads the View's current OnTouchListener out of its ListenerInfo, so we can chain to WeChat's.
    // This runs on every message bind while scrolling; both fields are resolved once per class.
    private fun getAttachedTouchListener(view: View): View.OnTouchListener? = runCatching {
        val info = view.cachedField("mListenerInfo").get(view) ?: return null
        info.cachedField("mOnTouchListener").get(info) as? View.OnTouchListener
    }.getOrNull()

    // ── gesture ──────────────────────────────────────────────────────────────
    //
    // This handles TWO entry paths into the same container:
    //   • Bubble (clickable child owns ACTION_DOWN): onInterceptTouchEvent above records the start
    //     and steals the stream once it is a left-drag, so the listener only sees MOVE onward with
    //     isDragging already set. The DOWN branch here never runs.
    //   • Blank area right of the bubble (no clickable descendant under the touch): ACTION_DOWN
    //     falls through to this listener. The container is a non-clickable RelativeLayout, so we must
    //     consume the DOWN (return true) or it receives no further MOVE events. We then detect the
    //     drag here ourselves, since onInterceptTouchEvent is no longer called once the container is
    //     the touch target.
    // requestDisallowInterceptTouchEvent(true) is deferred until a left-drag is confirmed, so a
    // vertical scroll starting on blank space still reaches the RecyclerView.
    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.dragDirection = null
                s.triggered = false
                s.longTriggered = false
                s.actionOverlay?.dismiss(animated = false)
                s.actionOverlay = null
                snapshotGesturePrefs(s)
                // Claim so the non-clickable container keeps receiving the stream (blank-area path).
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (s.dragDirection == null) {
                    val dx = event.rawX - s.startX
                    val dy = event.rawY - s.startY
                    detectDragDirection(dx, dy, s)?.let {
                        s.dragDirection = it
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                val direction = s.dragDirection
                if (direction != null) {
                    val dx = event.rawX - s.startX
                    // A revoke swipe that can also reopen the message for editing keeps following the
                    // finger up to the longer threshold, so the second commit distance is visible.
                    val limit = if (longReachable(s, direction)) s.longTriggerThreshold else s.triggerThreshold
                    v.translationX = when (direction) {
                        DragDirection.LEFT -> dx.coerceIn(-limit, 0f)
                        DragDirection.RIGHT -> dx.coerceIn(0f, limit)
                    }
                    val overlayProgress = when (direction) {
                        DragDirection.LEFT -> (-dx / s.triggerThreshold).coerceIn(0f, 1f)
                        DragDirection.RIGHT -> (dx / s.triggerThreshold).coerceIn(0f, 1f)
                    }
                    updateActionOverlay(v, s, direction, overlayProgress, longActive(s, direction, dx))
                    // Haptic tick tracks the live fireable state: buzz when crossing INTO the fire
                    // zone, and re-arm when sliding back out so a re-cross buzzes again.
                    val past = when (direction) {
                        DragDirection.LEFT -> dx <= -s.triggerThreshold
                        DragDirection.RIGHT -> dx >= s.triggerThreshold
                    }
                    if (past && !s.triggered) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    s.triggered = past
                    // A second tick marks the longer revoke-and-edit threshold.
                    val long = longActive(s, direction, dx)
                    if (long && !s.longTriggered) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    s.longTriggered = long
                    true
                } else {
                    // Not (yet) a horizontal drag; don't consume, so a vertical scroll can still be
                    // intercepted by the RecyclerView.
                    false
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - s.startX
                // Decide by the FINAL position, not whether the threshold was ever crossed: sliding
                // past the threshold and then back is an intentional cancel, so it must NOT fire.
                val direction = s.dragDirection
                val fire = when (direction) {
                    DragDirection.LEFT -> dx <= -s.triggerThreshold
                    DragDirection.RIGHT -> dx >= s.triggerThreshold
                    null -> false
                }
                // The long revoke swipe only applies when the message can also be edited
                // afterwards; otherwise it degrades to a plain revoke.
                val fireLong = direction != null && longActive(s, direction, dx)
                if (direction != null) {
                    v.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setInterpolator(springInterpolator)
                        .start()
                    s.actionOverlay?.dismiss(direction, animated = true, long = fireLong)
                    s.actionOverlay = null
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.dragDirection = null
                    if (fire) {
                        if (isSecondaryDirection(s, direction)) {
                            when (s.secondaryAction) {
                                SecondaryAction.REPEAT -> onSwipeRepeat(v, s)
                                SecondaryAction.EDIT -> onSwipeEdit(v, s)
                                SecondaryAction.REVOKE ->
                                    if (fireLong) onSwipeRevokeAndEdit(v, s) else onSwipeRevoke(v, s)
                            }
                        } else {
                            s.chattingContext?.get()?.let { onSwipeQuote(v, it) }
                        }
                    }
                    true
                } else {
                    s.chattingContext?.get()?.let(::hideChatInput)
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (s.dragDirection != null) {
                    val direction = s.dragDirection!!
                    v.animate().translationX(0f).setDuration(150).start()
                    s.actionOverlay?.dismiss(
                        direction,
                        animated = true,
                        long = longActive(s, direction, event.rawX - s.startX),
                    )
                    s.actionOverlay = null
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.dragDirection = null
                }
                false
            }

            else -> false
        }
    }

    private fun updateActionOverlay(
        row: View,
        s: SwipeState,
        direction: DragDirection,
        rawProgress: Float,
        longActive: Boolean,
    ) {
        val overlay = s.actionOverlay ?: SwipeActionOverlay(
            row,
            action = s.secondaryAction,
            secondaryDirection = if (s.secondarySwapped) DragDirection.LEFT else DragDirection.RIGHT,
        ).also { s.actionOverlay = it }
        // Keep the icon directly under the finger while dragging. Applying an overshoot
        // interpolator here makes its distance from the edge nonlinear relative to the row.
        val progress = rawProgress.coerceIn(0f, 1f)
        overlay.update(row, direction, progress, longActive)
    }

    /**
     * Whether the revoke swipe has been dragged past the longer threshold, which additionally
     * reopens the message for editing. Only the configured secondary direction qualifies.
     */
    private fun longActive(s: SwipeState, direction: DragDirection, dx: Float): Boolean =
        longReachable(s, direction) && abs(dx) >= s.longTriggerThreshold

    /**
     * Whether this swipe can continue into the longer revoke-and-edit gesture. The follow-up edit
     * requires a text message of the user's own, so other revokes stop at the plain threshold.
     */
    private fun longReachable(s: SwipeState, direction: DragDirection): Boolean =
        s.secondaryAction == SecondaryAction.REVOKE &&
                isSecondaryDirection(s, direction) &&
                s.msgInfo?.let(QuickRevokeAndEdit::isSupported) == true

    private class SwipeActionOverlay(
        row: View,
        private val action: SecondaryAction,
        private val secondaryDirection: DragDirection,
    ) {
        private val root = row.rootView as? ViewGroup
        private val size = 44.dpToPx(row.context)
        private val edgeMargin = 16.dpToPx(row.context)
        private val quoteIcon = SwipeActionIconView(row.context, ReplyIcon, SwipeActionColor.GREEN)
        private val repeatIcon = SwipeActionIconView(row.context, ExposurePlus1Icon, SwipeActionColor.BLUE)
        private val editIcon = SwipeActionIconView(row.context, EditIcon, SwipeActionColor.ORANGE)
        private val revokeIcon = SwipeActionIconView(row.context, UndoIcon, SwipeActionColor.RED)
        private val secondaryIcons = mapOf(
            SecondaryAction.REPEAT to repeatIcon,
            SecondaryAction.EDIT to editIcon,
            SecondaryAction.REVOKE to revokeIcon,
        )
        private val rootLocation = IntArray(2)
        private val rowLocation = IntArray(2)

        init {
            root?.overlay?.add(quoteIcon)
            root?.overlay?.add(repeatIcon)
            root?.overlay?.add(editIcon)
            root?.overlay?.add(revokeIcon)
            quoteIcon.layout(0, 0, size, size)
            repeatIcon.layout(0, 0, size, size)
            editIcon.layout(0, 0, size, size)
            revokeIcon.layout(0, 0, size, size)
            quoteIcon.alpha = 0f
            repeatIcon.alpha = 0f
            editIcon.alpha = 0f
            revokeIcon.alpha = 0f
        }

        fun update(row: View, direction: DragDirection, progress: Float, longActive: Boolean) {
            val root = root ?: return
            if (root.width <= 0) return

            root.getLocationOnScreen(rootLocation)
            row.getLocationOnScreen(rowLocation)
            val y = (rowLocation[1] - rootLocation[1] + (row.height - size) / 2f)
                .coerceIn(edgeMargin.toFloat(), (root.height - size - edgeMargin).toFloat())

            val leftHiddenX = -size - edgeMargin.toFloat()
            val leftVisibleX = edgeMargin.toFloat()
            val rightHiddenX = root.width + edgeMargin.toFloat()
            val rightVisibleX = root.width - size - edgeMargin.toFloat()

            // The action is fixed for the duration of the gesture, so it is resolved once here
            // instead of reading preferences on every MOVE event. A revoke swipe dragged past the
            // long threshold previews the follow-up edit action with the edit glyph.
            val longEdit = action == SecondaryAction.REVOKE && longActive
            val secondaryIcon = if (longEdit) editIcon else secondaryIcons.getValue(action)

            quoteIcon.y = y
            repeatIcon.y = y
            editIcon.y = y
            revokeIcon.y = y
            quoteIcon.translationX = 0f
            repeatIcon.translationX = 0f
            editIcon.translationX = 0f
            revokeIcon.translationX = 0f

            // Keep every secondary icon that is not the one currently previewed parked offscreen and
            // invisible. This also clears the revoke glyph once the long threshold swaps it for edit.
            secondaryIcons.forEach { (_, icon) ->
                if (icon != secondaryIcon) {
                    icon.alpha = 0f
                    icon.x = rightHiddenX
                }
            }

            // Which icon is active depends on the logical action bound to this physical direction,
            // not the direction itself — so both icons move correctly when directions are swapped.
            val (activeIcon, idleIcon) = if (direction == secondaryDirection)
                Pair(secondaryIcon, quoteIcon) else Pair(quoteIcon, secondaryIcon)

            if (direction == DragDirection.LEFT) {
                activeIcon.x = lerp(rightHiddenX, rightVisibleX, progress)
                activeIcon.alpha = progress
                idleIcon.x = leftHiddenX
                idleIcon.alpha = 0f
            } else {
                activeIcon.x = lerp(leftHiddenX, leftVisibleX, progress)
                activeIcon.alpha = progress
                idleIcon.x = rightHiddenX
                idleIcon.alpha = 0f
            }
        }

        fun dismiss(
            direction: DragDirection = DragDirection.LEFT,
            animated: Boolean,
            long: Boolean = false,
        ) {
            if (animated) {
                val root = root
                if (root == null || root.width <= 0) {
                    remove()
                    return
                }

                val secondaryIcon =
                    if (action == SecondaryAction.REVOKE && long) editIcon
                    else secondaryIcons.getValue(action)
                val targetIcon = if (direction == secondaryDirection) secondaryIcon else quoteIcon
                val offscreenX = when (direction) {
                    DragDirection.LEFT -> root.width + edgeMargin.toFloat()
                    DragDirection.RIGHT -> -size - edgeMargin.toFloat()
                }
                targetIcon.animate()
                    .x(offscreenX)
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction(::remove)
                    .start()
            } else {
                remove()
            }
        }

        private fun remove() {
            quoteIcon.animate().cancel()
            repeatIcon.animate().cancel()
            editIcon.animate().cancel()
            revokeIcon.animate().cancel()
            quoteIcon.translationX = 0f
            repeatIcon.translationX = 0f
            editIcon.translationX = 0f
            revokeIcon.translationX = 0f
            root?.overlay?.remove(quoteIcon)
            root?.overlay?.remove(repeatIcon)
            root?.overlay?.remove(editIcon)
            root?.overlay?.remove(revokeIcon)
        }

        private fun lerp(start: Float, end: Float, progress: Float): Float {
            return start + (end - start) * progress
        }
    }

    // Icon accent palette, split by light/dark mode. The circle is a soft-tinted backdrop; the icon
    // sits on top in a vivid variant of the same hue so it reads clearly against either theme.
    private enum class SwipeActionColor(
        val iconLight: Int,
        val circleLight: Int,
        val iconDark: Int,
        val circleDark: Int,
    ) {
        GREEN(
            iconLight = "#2E7D32".toColorInt(),
            circleLight = "#C8E6C9".toColorInt(),
            iconDark = "#A5D6A7".toColorInt(),
            circleDark = "#1B3A1E".toColorInt(),
        ),
        BLUE(
            iconLight = "#1565C0".toColorInt(),
            circleLight = "#BBDEFB".toColorInt(),
            iconDark = "#90CAF9".toColorInt(),
            circleDark = "#152A47".toColorInt(),
        ),
        ORANGE(
            iconLight = "#BF360C".toColorInt(),
            circleLight = "#FFCCBC".toColorInt(),
            iconDark = "#FFAB91".toColorInt(),
            circleDark = "#3E1000".toColorInt(),
        ),
        RED(
            iconLight = "#C62828".toColorInt(),
            circleLight = "#FFCDD2".toColorInt(),
            iconDark = "#EF9A9A".toColorInt(),
            circleDark = "#3E0000".toColorInt(),
        );

        fun iconColor(dark: Boolean) = if (dark) iconDark else iconLight
        fun circleColor(dark: Boolean) = if (dark) circleDark else circleLight
    }

    @SuppressLint("AppCompatCustomView")
    private class SwipeActionIconView(
        context: Context,
        iconDrawable: Drawable,
        color: SwipeActionColor,
    ) : ImageView(context) {
        init {
            val dark = context.isDarkMode
            scaleType = ScaleType.CENTER_INSIDE
            // Tint a fresh copy so the shared icon singleton keeps its default color.
            val drawable = iconDrawable.constantState?.newDrawable()?.mutate() ?: iconDrawable.mutate()
            drawable.colorFilter = PorterDuffColorFilter(color.iconColor(dark), PorterDuff.Mode.SRC_IN)
            setImageDrawable(drawable)
            imageTintList = null
            // Circular backdrop behind the glyph.
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color.circleColor(dark))
            }
            val pad = 10.dpToPx(context)
            setPadding(pad, pad, pad, pad)
        }
    }

    // Returns whether a physical direction maps to the secondary action (repeat/edit/revoke vs.
    // quote). secondary direction = RIGHT when not swapped, LEFT when swapped.
    private fun isSecondaryDirection(s: SwipeState, dir: DragDirection) =
        (dir == DragDirection.RIGHT) xor s.secondarySwapped

    /** Reads every gesture preference in one query and stores the result on [s]. */
    private fun snapshotGesturePrefs(s: SwipeState) {
        val values = KvStore.getBooleans(gesturePrefKeys)
        s.secondaryEnabled = values[PREF_ENABLE_SECONDARY] ?: false
        s.secondarySwapped = values[PREF_SWAP_DIRECTIONS] ?: false
        s.secondaryAction = when {
            values[PREF_USE_REVOKE] == true -> SecondaryAction.REVOKE
            values[PREF_USE_EDIT] == true -> SecondaryAction.EDIT
            else -> SecondaryAction.REPEAT
        }
    }

    private fun secondaryAction(): SecondaryAction = when {
        useRevokeInsteadOfRepeat -> SecondaryAction.REVOKE
        useEditInsteadOfRepeat -> SecondaryAction.EDIT
        else -> SecondaryAction.REPEAT
    }

    // WeChat allows revoking a message within two minutes of sending it.
    private fun isRevokeSupported(msgInfo: MessageInfo): Boolean =
        msgInfo.isSelfSender &&
            now() - Instant.fromEpochMilliseconds(msgInfo.createTime) <= 2.minutes

    private fun detectDragDirection(dx: Float, dy: Float, s: SwipeState): DragDirection? {
        if (abs(dx) <= s.touchSlop || abs(dx) <= abs(dy)) return null
        val dir = if (dx < 0) DragDirection.LEFT else DragDirection.RIGHT
        return if (isSecondaryDirection(s, dir)) {
            // Secondary direction: gated by the master toggle, then by per-action type support.
            if (!s.secondaryEnabled) null
            else when (s.secondaryAction) {
                SecondaryAction.REPEAT ->
                    if (s.msgInfo?.let(RepeatMessages::isSupported) == true) dir else null

                SecondaryAction.EDIT ->
                    if (s.msgInfo?.let(::isRevokeSupported) == true) dir else null

                SecondaryAction.REVOKE ->
                    if (s.msgInfo?.let(::isRevokeSupported) == true) dir else null
            }
        } else {
            dir // Quote side: always active.
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var action by remember { mutableStateOf(secondaryAction()) }
            var secondary by remember { mutableStateOf(enableSecondary) }
            var swap by remember { mutableStateOf(swapDirections) }
            val sec = stringResource(
                when (action) {
                    SecondaryAction.REPEAT -> R.string.chat_repeat_menu
                    SecondaryAction.EDIT -> R.string.chat_swipe_action_edit
                    SecondaryAction.REVOKE -> R.string.chat_swipe_action_revoke
                }
            )
            fun selectAction(selected: SecondaryAction) {
                action = selected
                useEditInsteadOfRepeat = selected == SecondaryAction.EDIT
                useRevokeInsteadOfRepeat = selected == SecondaryAction.REVOKE
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_swipe_message_operations_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_swipe_secondary),
                                description = stringResource(R.string.chat_swipe_secondary_description, sec),
                                checked = secondary,
                                onCheckedChange = {
                                    secondary = it
                                    enableSecondary = it
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                title = stringResource(R.string.chat_repeat_menu),
                                description = stringResource(R.string.chat_swipe_repeat_description),
                                iconPlaceholder = false,
                                selected = action == SecondaryAction.REPEAT,
                                enabled = secondary,
                                trailingDivider = true,
                                onClick = { selectAction(SecondaryAction.REPEAT) },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                title = stringResource(R.string.chat_swipe_action_edit),
                                description = stringResource(R.string.chat_swipe_edit_description),
                                iconPlaceholder = false,
                                selected = action == SecondaryAction.EDIT,
                                enabled = secondary,
                                trailingDivider = true,
                                onClick = { selectAction(SecondaryAction.EDIT) },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                title = stringResource(R.string.chat_swipe_action_revoke),
                                description = stringResource(R.string.chat_swipe_revoke_description),
                                iconPlaceholder = false,
                                selected = action == SecondaryAction.REVOKE,
                                enabled = secondary,
                                onClick = { selectAction(SecondaryAction.REVOKE) },
                            )
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_swipe_swap),
                                description = stringResource(R.string.chat_swipe_swap_description, sec),
                                checked = swap,
                                onCheckedChange = {
                                    swap = it
                                    swapDirections = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    // ── swipe actions ──────────────────────────────────────────────────────────

    private fun hideChatInput(chattingContext: Any) {
        val chatFooter = chatFooter(chattingContext)
        val method = methodHideChatInput.method
        if (method.parameterCount == 1) {
            method.invoke(chatFooter, true)
        } else {
            method.invoke(chatFooter, 0, true)
        }
    }

    /**
     * The API manager and ChatFooter of a chatting UI never change for the lifetime of that
     * chatting context, so the reflection chain only needs to run once per chat instead of once
     * per tap on the blank area of the message list.
     */
    private fun chatFooter(chattingContext: Any): ChatFooter = synchronized(chatFooterCache) {
        chatFooterCache[chattingContext]?.get()?.let { return it }
        val apiMan = chattingContext.reflekt()
            .firstField { type = WeServiceApi.apiManagerClass }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, classChattingUiFootComponent.clazz)
        val footer = api.reflekt()
            .firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
            .get()!! as ChatFooter
        chatFooterCache[chattingContext] = WeakReference(footer)
        footer
    }

    private fun onSwipeQuote(originalView: View, chattingContext: Any) {
        val chatFooter = chatFooter(chattingContext)
        val chatHolder = originalView.tag.reflekt().getField("chatHolder", true)!!
        val msgInfo = methodGetMsgInfo.method.invoke(null, chatHolder, chattingContext)!!
        WeMessageApi.setReferringMessage(chatFooter, msgInfo)
    }

    private fun onSwipeRepeat(view: View, s: SwipeState) {
        val msgInfo = s.msgInfo ?: return
        if (!s.secondaryEnabled || !RepeatMessages.isSupported(msgInfo)) return

        val context = view.context
        CoroutineScope(Dispatchers.IO).launch {
            val sent = RepeatMessages.repeatMessage(msgInfo)
            if (!sent) showToastSuspend(context, context.localizedChatString(R.string.chat_repeat_failed))
        }
    }

    private fun onSwipeEdit(view: View, s: SwipeState) {
        val msgInfo = s.msgInfo ?: return
        // Text messages that support re-editing are revoked and restored to the input box; any other
        // message that can no longer be re-edited is simply revoked.
        if (QuickRevokeAndEdit.isSupported(msgInfo)) {
            QuickRevokeAndEdit.quickRevokeAndEdit(view.context, msgInfo)
        } else {
            onSwipeRevoke(view, s)
        }
    }

    /**
     * Revokes and reopens the message for editing. Reached by dragging a revoke swipe past the
     * longer threshold, so it deliberately shares the implementation with the edit action.
     */
    private fun onSwipeRevokeAndEdit(view: View, s: SwipeState) {
        val msgInfo = s.msgInfo ?: return
        if (!QuickRevokeAndEdit.isSupported(msgInfo)) return
        QuickRevokeAndEdit.quickRevokeAndEdit(view.context, msgInfo)
    }

    private fun onSwipeRevoke(view: View, s: SwipeState) {
        val msgInfo = s.msgInfo ?: return
        if (!isRevokeSupported(msgInfo)) return

        val context = view.context
        CoroutineScope(Dispatchers.IO).launch {
            if (!WeMessageApi.revokeMsg(msgInfo)) {
                showToastSuspend(context, context.localizedChatString(R.string.chat_swipe_revoke_failed))
            }
        }
    }

    private val classChattingUiFootComponent by dexClass()

    private val methodGetMsgInfo by dexMethod()
}
