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
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
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

    /** 卡片组上下留白: 只在组的首/末行外侧留, 组内行之间不留。 */
    private const val OUTER_INSET_DP = 8

    /** 相邻两行之间的原生空隙超过该值即视为分组边界。 */
    private const val GROUP_GAP_THRESHOLD_DP = 2

    private const val ADDRESS_UI_FRAGMENT = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"
    private const val BIZ_ENTRANCE_VIEW = "com.tencent.mm.ui.contact.BizContactEntranceView"

    private const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
    private const val DARK_SURFACE = 0xFF1F1F21.toInt()
    private const val LIGHT_PAGE = 0xFFF0F0F3.toInt()
    private const val DARK_PAGE = 0xFF101012.toInt()
    private const val LIGHT_RIPPLE = 0x12000000
    private const val DARK_RIPPLE = 0x1FFFFFFF

    // 与会话列表一致: 打开特性即生效, 内层开关只用来临时关掉圆角卡片。
    private var islandEnabled by prefOption("beautify_home_lists_island", true)

    /** 一行在合并后的卡片组中的位置, 决定哪几个角是圆角。 */
    private enum class RowPosition { SINGLE, TOP, MIDDLE, BOTTOM }

    private class RowState(
        var original: Drawable?,
        var applied: Drawable,
        var position: RowPosition,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
    ) {
        /** 行内不透明白底子 View 的原背景, 关闭功能时还原。 */
        val childBackgrounds = IdentityHashMap<View, Drawable?>()
    }

    private val rows = WeakHashMap<View, RowState>()

    /** 最近一次布局算出的分组位置; 行被回收再绑定时先用它, 下一次布局会纠正。 */
    private val rowPositions = WeakHashMap<View, RowPosition>()

    /** 通讯录顶部那块手工构建的入口列表, 不属于主列表, 需要单独套卡片。 */
    private val contactsHeaders = WeakHashMap<ViewGroup, Boolean>()

    /** 已确认属于主页签的列表, 切换开关时据此立即重刷, 不用等下一次滚动/布局。 */
    private val lists = WeakHashMap<View, Boolean>()

    /** 统一的透明背景, 用来把行内子 View 的白底让出来, 让卡片自身的圆角可见。 */
    private val transparent = ColorDrawable(Color.TRANSPARENT)

    /** ViewHolder.itemView 每次反射解析都很贵, 按 holder 类缓存。 */
    private val itemViewFields = ConcurrentHashMap<Class<*>, Field>()

    override fun onEnable() {
        hookRecyclerAdapter()
        hookListLayout()
        hookContactsHeader()
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

    /**
     * 通讯录顶部由 MvvmAddressUIFragment 手工搭的入口块 (新的朋友/群聊/企业微信联系人…)
     * 不在主列表里, 所以 onLayout 钩子碰不到它, 单独监听 fragment 创建后套卡片。
     */
    private fun hookContactsHeader() {
        val fragmentClass = loadHostClass(ADDRESS_UI_FRAGMENT) ?: return
        val method = runCatching {
            fragmentClass.reflekt().firstMethod { parameters(Bundle::class) }
        }.onFailure {
            WeLogger.w(TAG, "MvvmAddressUIFragment entry not found: $it")
        }.getOrNull() ?: return
        method.hookAfter {
            val entrance = thisObject.reflekt()
                .firstFieldOrNull { type { it.name == BIZ_ENTRANCE_VIEW } }
                ?.get() as? View ?: return@hookAfter
            val header = entrance.parent as? ViewGroup ?: return@hookAfter
            contactsHeaders[header] = true
            header.post { styleContactsHeader(header) }
        }
    }

    private fun styleContactsHeader(header: ViewGroup) {
        if (!isEnabled || !islandEnabled) return
        decorateRow(header, RowPosition.SINGLE)
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
        decorateGrouped(group)
    }

    /**
     * 同一个分组内的行合成一张卡片: 组内行不再各自留上下空隙, 也不再画描边,
     * 只有组的第一行圆上角、最后一行圆下角。分组边界取宿主列表自身的行间空隙,
     * 第一个内容行 (资料卡) 和最后一个内容行始终单独成组。
     */
    private fun decorateGrouped(group: ViewGroup) {
        val density = group.resources.displayMetrics.density
        val gapThreshold = (GROUP_GAP_THRESHOLD_DP * density).roundToInt().coerceAtLeast(1)
        val contentIndices = (0 until group.childCount).filter { hasVisibleContent(group.getChildAt(it)) }
        if (contentIndices.isEmpty()) return

        val lastContentIndex = contentIndices.last()
        contentIndices.forEachIndexed { position, index ->
            val row = group.getChildAt(index)
            val previous = contentIndices.getOrNull(position - 1)
            val next = contentIndices.getOrNull(position + 1)
            val topBreak = previous == null || previous == 0 || index == lastContentIndex ||
                row.top - group.getChildAt(previous).bottom > gapThreshold
            val bottomBreak = next == null || next == lastContentIndex || index == 0 ||
                group.getChildAt(next).top - row.bottom > gapThreshold
            val rowPosition = when {
                topBreak && bottomBreak -> RowPosition.SINGLE
                topBreak -> RowPosition.TOP
                bottomBreak -> RowPosition.BOTTOM
                else -> RowPosition.MIDDLE
            }
            rowPositions[row] = rowPosition
            decorateRow(row, rowPosition)
        }
    }

    /** 底部的空白占位行没有任何可见内容, 给它加卡片就成了那条莫名其妙的空行。 */
    private fun hasVisibleContent(view: View): Boolean {
        if (view.visibility != View.VISIBLE || view.height <= 0) return false
        when (view) {
            is TextView -> if (!view.text.isNullOrBlank()) return true
            is ImageView -> if (view.drawable != null) return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasVisibleContent(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun refreshAll() {
        runOnUiThread {
            if (!isEnabled || !islandEnabled) {
                restoreAll()
                return@runOnUiThread
            }
            lists.keys.toList().forEach(::decorateList)
            contactsHeaders.keys.toList().forEach(::styleContactsHeader)
        }
    }

    private fun decorate(row: View) {
        if (!isDecoratableRow(row)) return
        if (!hasVisibleContent(row)) return
        decorateRow(row, rowPositions[row] ?: RowPosition.MIDDLE)
    }

    /** Card styling for a row already known to belong to a confirmed LauncherUI tab list. */
    private fun decorateRow(row: View, position: RowPosition) {
        val density = row.resources.displayMetrics.density
        val horizontalInset = (SIDE_INSET_DP * density).roundToInt()
        val state = rows[row] ?: RowState(
            row.background,
            buildCardBackground(row, position, horizontalInset),
            position,
            row.paddingLeft,
            row.paddingTop,
            row.paddingRight,
            row.paddingBottom,
        ).also { rows[row] = it }

        if (state.position != position) {
            state.position = position
            state.applied = buildCardBackground(row, position, horizontalInset)
        }

        // 只让出左右边距; 组内行之间不再留空隙, 这样它们才会连成一张卡片。
        val left = state.paddingLeft + horizontalInset
        val right = state.paddingRight + horizontalInset
        if (row.paddingLeft != left || row.paddingRight != right) {
            row.setPadding(left, state.paddingTop, right, state.paddingBottom)
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
        contactsHeaders.clear()
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

    /**
     * 组内中间行不画描边, 否则每行四周的边框会在卡片内部形成一条条分割线;
     * 只靠圆角区分组的上下边缘。
     */
    private fun buildCardBackground(row: View, position: RowPosition, horizontalInset: Int): Drawable {
        val density = row.resources.displayMetrics.density
        val radius = CORNER_RADIUS_DP * density
        val outerInset = (OUTER_INSET_DP * density).roundToInt()
        // 组的上/下外侧留一点空隙, 否则最顶部那张卡片会贴着状态栏, 上圆角看不出来。
        val topInset = if (position == RowPosition.TOP || position == RowPosition.SINGLE) outerInset else 0
        val bottomInset = if (position == RowPosition.BOTTOM || position == RowPosition.SINGLE) outerInset else 0
        val dark = row.context.isDarkMode

        fun cornerRadii(): FloatArray {
            val top = if (position == RowPosition.TOP || position == RowPosition.SINGLE) radius else 0f
            val bottom = if (position == RowPosition.BOTTOM || position == RowPosition.SINGLE) radius else 0f
            return floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
        }

        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = cornerRadii()
            setColor(if (dark) DARK_SURFACE else LIGHT_SURFACE)
        }
        val content = InsetDrawable(card, horizontalInset, topInset, horizontalInset, bottomInset)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = cornerRadii()
            setColor(Color.WHITE)
        }
        val rippleMask = InsetDrawable(mask, horizontalInset, topInset, horizontalInset, bottomInset)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(if (dark) DARK_RIPPLE else LIGHT_RIPPLE),
            content,
            rippleMask,
        )
        // 行背景自己铺一层页面底色, 卡片四周的留白才不会露出宿主列表的白色底,
        // 否则白卡片接白底看不出圆角, 通讯录顶部尤其明显。
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
