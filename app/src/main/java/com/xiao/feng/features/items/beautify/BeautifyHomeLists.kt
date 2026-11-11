package com.xiao.feng.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
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
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.ui.WeConversationListViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.isDarkMode
import com.xiao.feng.utils.android.runOnUiThread
import java.lang.reflect.Field
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Applies the conversation list's rounded "island" rows to the other main tabs
 * (contacts / discover / me).
 *
 * Both the RecyclerView and ListView paths are covered so the feature works regardless of
 * which list implementation a page happens to use. Only rows whose window root is the
 * LauncherUI decor are touched, and conversation / chatting rows are left to their own
 * features.
 */
object BeautifyHomeLists : ClickableFeature() {

    override val technicalId = "美化主页列表"
    override val nameRes = R.string.feature_beautify_home_lists_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_home_lists_description

    private const val TAG = "BeautifyHomeLists"

    private const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"

    private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val RECYCLER_ADAPTER_CLASS = "androidx.recyclerview.widget.RecyclerView\$Adapter"

    private const val CORNER_RADIUS_DP = 20
    private const val SIDE_INSET_DP = 12
    private const val ROW_GAP_DP = 6

    private const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
    private const val DARK_SURFACE = 0xFF1F1F21.toInt()
    private const val LIGHT_PAGE = 0xFFF0F0F3.toInt()
    private const val DARK_PAGE = 0xFF101012.toInt()
    private const val LIGHT_STROKE = 0x14000000
    private const val DARK_STROKE = 0x1FFFFFFF
    private const val LIGHT_RIPPLE = 0x12000000
    private const val DARK_RIPPLE = 0x1FFFFFFF

    // 与会话列表一致: 打开特性即生效, 内层开关只用来临时关掉圆角卡片。
    private var islandEnabled by prefOption("beautify_home_lists_island", true)

    private class RowState(
        var original: Drawable?,
        val applied: Drawable,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
    ) {
        /** 行内不透明白底子 View 的原背景, 关闭功能时还原。 */
        val childBackgrounds = IdentityHashMap<View, Drawable?>()
    }

    private val rows = WeakHashMap<View, RowState>()

    /** 已确认属于主页签的列表, 切换开关时据此立即重刷, 不用等下一次滚动/布局。 */
    private val lists = WeakHashMap<View, Boolean>()

    /** 统一的透明背景, 用来把行内子 View 的白底让出来, 让卡片自身的圆角可见。 */
    private val transparent = ColorDrawable(Color.TRANSPARENT)

    /** ViewHolder.itemView 每次反射解析都很贵, 按 holder 类缓存。 */
    private val itemViewFields = ConcurrentHashMap<Class<*>, Field>()

    override fun onEnable() {
        hookRecyclerAdapter()
        hookListLayout()
        keepAppliedBackground()
    }

    /**
     * 微信进程里的 androidx.RecyclerView 与模块自带的不是同一个 Class 对象,
     * 必须用宿主的 classloader 取类, 否则 hook 挂上去也不会被调用。
     */
    private fun loadHostClass(name: String): Class<*>? = runCatching {
        Class.forName(name, false, HostInfo.application.classLoader)
    }.onFailure {
        WeLogger.w(TAG, "host class unavailable: $name ($it)")
    }.getOrNull()

    private fun hookRecyclerAdapter() {
        val adapterClass = loadHostClass(RECYCLER_ADAPTER_CLASS) ?: return
        val bindMethod = runCatching {
            adapterClass.reflekt().firstMethod { name = "bindViewHolder" }
        }.onFailure {
            WeLogger.w(TAG, "RecyclerView.Adapter.bindViewHolder not found: $it")
        }.getOrNull() ?: return
        bindMethod.hookAfter {
            if (!isEnabled || !islandEnabled) return@hookAfter
            // 不能用模块里的 RecyclerView.ViewHolder 强转 (跨 classloader), itemView 直接反射拿。
            val holder = args[0]!!
            val field = itemViewFields.getOrPut(holder.javaClass) {
                holder.reflekt().firstField { name = "itemView"; superclass() }.self.makeAccessible()
            }
            val row = field.get(holder) as View
            // 聊天页/其它 Activity 的行没必要再排一帧处理。
            val activity = row.context.findActivity() ?: return@hookAfter
            if (activity.javaClass.name != LAUNCHER_UI_CLASS) return@hookAfter
            // bind 时行还没 attach, isMainTabView 的 rootView 判断会失败, 推迟一帧。
            if (row.isAttachedToWindow) decorate(row) else row.post { decorate(row) }
        }
    }

    private fun hookListLayout() {
        loadHostClass(RECYCLER_VIEW_CLASS)?.let { recyclerViewClass ->
            // onLayout 可能声明在父类上, 先找自身再沿继承链找。
            val method = firstOnLayout(recyclerViewClass)
            if (method == null) {
                WeLogger.w(TAG, "RecyclerView.onLayout not found")
            } else {
                method.hookAfter {
                    val view = thisObject as View
                    if (!isRecyclerView(view)) return@hookAfter
                    decorateList(view)
                }
            }
        }
        val absListMethod = firstOnLayout(AbsListView::class.java)
        if (absListMethod == null) {
            WeLogger.w(TAG, "AbsListView.onLayout not found")
        } else {
            absListMethod.hookAfter {
                val view = thisObject as View
                if (view !is AbsListView) return@hookAfter
                decorateList(view)
            }
        }
    }

    private fun firstOnLayout(clazz: Class<*>) =
        runCatching { clazz.reflekt().firstMethod { name = "onLayout" } }.getOrNull()
            ?: runCatching {
                clazz.reflekt().firstMethod {
                    name = "onLayout"
                    superclass()
                }
            }.getOrNull()

    /** 模块自带的 androidx 与宿主不是同一个 Class 对象, 只能沿类名继承链判断。 */
    private fun isRecyclerView(view: View): Boolean {
        var clazz: Class<*>? = view.javaClass
        while (clazz != null) {
            if (clazz.name == RECYCLER_VIEW_CLASS) return true
            clazz = clazz.superclass
        }
        return false
    }

    private fun keepAppliedBackground() {
        runCatching {
            View::class.reflekt().firstMethod { name = "setBackgroundDrawable" }.hookBefore {
                val row = thisObject as View
                val state = rows[row] ?: return@hookBefore
                if (!isDecoratableRow(row)) {
                    // 行被回收给了别的页面, 把卡片样式完整撤掉, 不能再塞回去。
                    rows.remove(row)
                    row.setPadding(
                        state.paddingLeft, state.paddingTop,
                        state.paddingRight, state.paddingBottom,
                    )
                    state.childBackgrounds.forEach { (child, background) ->
                        child.background = background
                    }
                    return@hookBefore
                }
                if (args[0] !== state.applied) {
                    state.original = args[0] as Drawable?
                    args[0] = state.applied
                }
            }
        }.onFailure { WeLogger.w(TAG, "hook View.setBackgroundDrawable failed: $it") }
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
                                    refreshAll()
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

    private fun decorateList(list: View) {
        if (!isEnabled || !islandEnabled) return
        // onLayout runs on every list layout pass while rows are recycled, so confirm the list is
        // a LauncherUI tab once and then skip the per-child activity/ancestor checks below.
        if (lists[list] != true) {
            if (!isMainTabView(list)) return
            if (isExcludedContainer(list)) return
        }
        // The card insets supply the separation; native dividers would draw a seam
        // across the gaps between rows.
        if (list is ListView && (list.divider != null || list.dividerHeight != 0)) {
            list.divider = null
            list.dividerHeight = 0
        }
        val group = list as? ViewGroup ?: return
        lists[list] = true
        for (index in 0 until group.childCount) {
            decorateConfirmed(group.getChildAt(index))
        }
    }

    private fun refreshAll() {
        runOnUiThread {
            if (!isEnabled || !islandEnabled) {
                restoreAll()
                return@runOnUiThread
            }
            lists.keys.toList().forEach(::decorateList)
        }
    }

    private fun decorate(row: View) {
        if (!isDecoratableRow(row)) return
        decorateConfirmed(row)
    }

    /** Card styling for a row already known to belong to a confirmed LauncherUI tab list. */
    private fun decorateConfirmed(row: View) {
        val density = row.resources.displayMetrics.density
        val horizontalInset = (SIDE_INSET_DP * density).roundToInt()
        val verticalInset = (ROW_GAP_DP * density / 2f).roundToInt()
        val state = rows[row] ?: RowState(
            row.background,
            buildIslandBackground(row, verticalInset),
            row.paddingLeft,
            row.paddingTop,
            row.paddingRight,
            row.paddingBottom,
        ).also { rows[row] = it }

        // 行内容也要让出卡片描边和行间距, 否则文字会压在卡片圆角外面。
        val left = state.paddingLeft + horizontalInset
        val top = state.paddingTop + verticalInset
        val right = state.paddingRight + horizontalInset
        val bottom = state.paddingBottom + verticalInset
        if (row.paddingLeft != left || row.paddingTop != top ||
            row.paddingRight != right || row.paddingBottom != bottom
        ) {
            row.setPadding(left, top, right, bottom)
        }
        if (row is ViewGroup) hideChildBackgrounds(row, state)
        if (row.background !== state.applied) row.background = state.applied
    }

    /**
     * 微信的行会把不透明白底画在行内第一个容器上, 卡片背景在它下面完全被盖住 ——
     * 这就是之前"看不出效果"的原因。这里把行内子 View 的背景让出来, 由卡片自己提供底色。
     */
    private fun hideChildBackgrounds(row: ViewGroup, state: RowState) {
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            val background = child.background ?: continue
            if (background === transparent) continue
            state.childBackgrounds[child] = background
            child.background = transparent
        }
    }

    private fun isDecoratableRow(row: View): Boolean {
        if (!isEnabled || !islandEnabled) return false
        if (!isMainTabView(row)) return false
        if (isExcludedContainer(row)) return false
        return true
    }

    /** 会话列表与聊天页 (部分版本内嵌在 LauncherUI 里) 不能被套成主页卡片。 */
    private fun isExcludedContainer(view: View): Boolean {
        val conversation = WeConversationListViewApi.currentContainer()
        var node: View? = view
        while (node != null) {
            if (node === conversation) return true
            val name = node.javaClass.name
            if (name.contains("Conversation") || name.contains("Chatting")) return true
            node = node.parent as? View
        }
        return false
    }

    private fun restoreAll() {
        val snapshot = rows.entries.toList()
        rows.clear()
        snapshot.forEach { (row, state) ->
            row.setPadding(
                state.paddingLeft, state.paddingTop,
                state.paddingRight, state.paddingBottom,
            )
            state.childBackgrounds.forEach { (child, background) ->
                child.background = background
            }
            if (row.background === state.applied) {
                row.background = state.original
            }
        }
    }

    private fun buildIslandBackground(row: View, verticalInset: Int): Drawable {
        val density = row.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * density
        val horizontalInset = (SIDE_INSET_DP * density).roundToInt()
        val dark = row.context.isDarkMode

        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (dark) DARK_SURFACE else LIGHT_SURFACE)
            // 主页签本身已经是白底/深底, 没有描边时卡片和背景会糊在一起看不出圆角。
            setStroke(
                (0.5f * density).roundToInt().coerceAtLeast(1),
                if (dark) DARK_STROKE else LIGHT_STROKE,
            )
        }
        val content = InsetDrawable(card, horizontalInset, verticalInset, horizontalInset, verticalInset)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        val rippleMask =
            InsetDrawable(mask, horizontalInset, verticalInset, horizontalInset, verticalInset)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(if (dark) DARK_RIPPLE else LIGHT_RIPPLE),
            content,
            rippleMask,
        )
        // 卡片四周留出的空隙用列表页底色填平, 相邻卡片之间才有"圆角岛"的间隔。
        val page = ColorDrawable(if (dark) DARK_PAGE else LIGHT_PAGE)
        return LayerDrawable(arrayOf(page, ripple))
    }

    private fun isMainTabView(view: View): Boolean {
        val activity = view.context.findActivity() ?: return false
        if (activity.javaClass.name != LAUNCHER_UI_CLASS) return false
        return view.rootView === activity.window.decorView
    }

    /**
     * Contexts in a view hierarchy never change activity, so resolve the wrapper chain once
     * instead of walking it on every row bind/layout.
     */
    private val activities = WeakHashMap<Context, Activity>()

    private fun Context.findActivity(): Activity? {
        if (this is Activity) return this
        activities[this]?.let { return it }
        val activity = (this as? ContextWrapper)?.baseContext?.findActivity() ?: return null
        activities[this] = activity
        return activity
    }
}
