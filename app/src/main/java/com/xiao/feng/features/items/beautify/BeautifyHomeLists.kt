package com.xiao.feng.features.items.beautify

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.android.isDarkMode
import com.xiao.feng.utils.android.runOnUiThread
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Applies the conversation list's rounded "island" rows to the other main tabs
 * (contacts / discover / me).
 *
 * Both the RecyclerView and ListView paths are covered so the feature works regardless of
 * which list implementation a page happens to use. Only rows whose window root is the
 * LauncherUI decor are touched, and conversation rows keep the dedicated styling that
 * [BeautifyConversationList] already provides.
 */
object BeautifyHomeLists : ClickableFeature() {

    override val technicalId = "美化主页列表"
    override val nameRes = R.string.feature_beautify_home_lists_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_home_lists_description

    private const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
    private const val CONVERSATION_RECYCLER_CLASS =
        "com.tencent.mm.ui.conversation.recycler.ConversationRecyclerView"
    private const val CONVERSATION_LIST_CLASS =
        "com.tencent.mm.ui.conversation.ConversationListView"

    private const val CORNER_RADIUS_DP = 20
    private const val SIDE_INSET_DP = 12
    private const val TOP_GAP_DP = 5

    private const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
    private const val DARK_SURFACE = 0xFF1F1F21.toInt()
    private const val LIGHT_RIPPLE = 0x12000000
    private const val DARK_RIPPLE = 0x1FFFFFFF

    private var islandEnabled by prefOption("beautify_home_lists_island", false)

    private class RowState(var original: Drawable?, val applied: Drawable)

    private val rows = WeakHashMap<View, RowState>()

    override fun onEnable() {
        Class.forName("androidx.recyclerview.widget.RecyclerView\$Adapter").reflekt().firstMethod {
            name = "bindViewHolder"
        }.hookAfter {
            val holder = args[0] as RecyclerView.ViewHolder
            // At bind time the row is not attached yet, so the window-root check in
            // decorate() cannot succeed. Post it to the next frame, after attach.
            val row = holder.itemView
            row.post { decorate(row) }
        }

        AbsListView::class.reflekt().firstMethod {
            name = "onLayout"
        }.hookAfter {
            val list = thisObject as AbsListView
            if (!isEnabled || !islandEnabled) return@hookAfter
            if (!isMainTabView(list)) return@hookAfter
            if (isConversationList(list)) return@hookAfter
            // The card insets supply the separation; native dividers would draw a seam
            // across the gaps between rows.
            if (list is ListView && (list.divider != null || list.dividerHeight != 0)) {
                list.divider = null
                list.dividerHeight = 0
            }
            for (index in 0 until list.childCount) {
                decorate(list.getChildAt(index))
            }
        }

        View::class.reflekt().firstMethod {
            name = "setBackgroundDrawable"
        }.hookBefore {
            val state = rows[thisObject as View] ?: return@hookBefore
            if (args[0] !== state.applied) {
                state.original = args[0] as Drawable?
                args[0] = state.applied
            }
        }
    }

    override fun onDisable() {
        runOnUiThread { restoreAll() }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var island by remember { mutableStateOf(islandEnabled) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_home_lists_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.beautify_home_lists_island),
                                description = stringResource(R.string.beautify_home_lists_island_summary),
                                checked = island,
                                onCheckedChange = {
                                    island = it
                                    islandEnabled = it
                                    runOnUiThread { if (!it) restoreAll() }
                                },
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

    private fun decorate(row: View) {
        if (!isEnabled || !islandEnabled) return
        if (!isMainTabView(row)) return
        if (findParent(row, CONVERSATION_RECYCLER_CLASS) != null) return
        if (findParent(row, CONVERSATION_LIST_CLASS) != null) return

        val state = rows[row]
        if (state != null && row.background === state.applied) return
        val original = state?.original ?: row.background
        val applied = buildIslandBackground(row)
        rows[row] = RowState(original, applied)
        row.background = applied
    }

    private fun restoreAll() {
        val snapshot = rows.entries.toList()
        rows.clear()
        snapshot.forEach { (row, state) ->
            if (row.background === state.applied) {
                row.background = state.original
            }
        }
    }

    private fun buildIslandBackground(row: View): Drawable {
        val density = row.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * density
        val horizontalInset = (SIDE_INSET_DP * density).roundToInt()
        val topInset = (TOP_GAP_DP * density).roundToInt()
        val dark = row.context.isDarkMode

        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (dark) DARK_SURFACE else LIGHT_SURFACE)
        }
        val content = InsetDrawable(card, horizontalInset, topInset, horizontalInset, 0)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val rippleMask = InsetDrawable(mask, horizontalInset, topInset, horizontalInset, 0)
        return RippleDrawable(
            ColorStateList.valueOf(if (dark) DARK_RIPPLE else LIGHT_RIPPLE),
            content,
            rippleMask,
        )
    }

    private fun isMainTabView(view: View): Boolean {
        val activity = view.context.findActivity() ?: return false
        if (activity.javaClass.name != LAUNCHER_UI_CLASS) return false
        return view.rootView === activity.window.decorView
    }

    private fun isConversationList(list: View): Boolean {
        val name = list.javaClass.name
        return name == CONVERSATION_LIST_CLASS || name == CONVERSATION_RECYCLER_CLASS
    }

    private fun findParent(view: View, className: String): View? {
        var parent = view.parent
        while (parent is View) {
            if (parent.javaClass.name == className) return parent
            parent = parent.parent
        }
        return null
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
