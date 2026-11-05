package com.xiao.feng.features.api.ui

import android.view.View
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.xiao.feng.R
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.api.core.WeMessageApi
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.WeLogger
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    override val technicalId = "消息 View 创建监听服务"
    override val nameRes = R.string.feature_we_chat_message_view_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_chat_message_view_api_description

    fun interface ICreateViewListener {
        fun onCreateView(
            param: HookParam, view: View
        )
    }

    interface IMessageViewLifecycleListener {
        fun onMessageViewAttached(view: View, message: MessageInfo) {}
        fun onMessageViewDetached(view: View, message: MessageInfo) {}
        fun onMessageViewRecycled(view: View, message: MessageInfo) {}
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()
    private val lifecycleListeners = CopyOnWriteArrayList<IMessageViewLifecycleListener>()
    private val currentBindings =
        Collections.synchronizedMap(WeakHashMap<View, MessageInfo>())
    private val attachStateListeners =
        Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())

    /**
     * Message resolved for the current bind, keyed by identity of the adapter item passed to
     * onBindView. Every feature listener receives the same bind event and would otherwise walk
     * the model itself, so the work is done once here. A single entry is enough because binds
     * are sequential and listeners run synchronously right after it.
     */
    private val bindLock = Any()
    private var bindItem: Any? = null
    private var bindMessage: MessageInfo? = null

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    fun addLifecycleListener(listener: IMessageViewLifecycleListener) {
        if (!lifecycleListeners.contains(listener)) {
            lifecycleListeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: IMessageViewLifecycleListener) {
        val removed = lifecycleListeners.remove(listener)
        WeLogger.i(
            TAG,
            "lifecycle listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${lifecycleListeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    private val methodChatItemOnBindView by dexMethod()

    private val methodChatItemOnViewRecycled by dexMethod()

    private val methodChatItemSetNickname by dexMethod()

    override fun onEnable() {
        methodChatItemSetNickname.hookAfter {
            if (args[1] != null) return@hookAfter
            val holder = args[0] ?: return@hookAfter
            val userTv = holder.reflekt()
                .firstFieldOrNull { name = "userTV"; superclass() }
                ?.get() as? TextView ?: return@hookAfter
            userTv.text = null
        }

        methodChatItemOnBindView.hookAfter {
            val holder = args[0]!!
            val view = holderView(holder)
            val message = getMsgInfoFromParam(this)
            // Listeners below (and other features) ask for the same binding again; keep the result
            // so a single bind does not repeat the reflection walk per listener.
            synchronized(bindLock) {
                bindItem = args[1]
                bindMessage = message
            }
            ensureAttachStateListener(view)

            val previous = synchronized(currentBindings) { currentBindings[view] }
            val bindingChanged = previous?.instance !== message.instance
            if (view.isAttachedToWindow && bindingChanged && previous != null) {
                dispatchLifecycle { it.onMessageViewDetached(view, previous) }
            }
            synchronized(currentBindings) {
                currentBindings[view] = message
            }
            if (view.isAttachedToWindow && bindingChanged) {
                dispatchLifecycle { it.onMessageViewAttached(view, message) }
            }

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }

        methodChatItemOnViewRecycled.hookBefore {
            val holder = args[0]!!
            val view = holderView(holder)
            val message = synchronized(currentBindings) { currentBindings[view] } ?: return@hookBefore
            dispatchLifecycle { it.onMessageViewRecycled(view, message) }
            synchronized(currentBindings) {
                currentBindings.remove(view)
            }
        }
    }

    private fun ensureAttachStateListener(view: View) {
        synchronized(attachStateListeners) {
            if (attachStateListeners.containsKey(view)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewAttached(view, message) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewDetached(view, message) }
                }
            }
            attachStateListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
        }
    }

    private fun dispatchLifecycle(callback: (IMessageViewLifecycleListener) -> Unit) {
        for (listener in lifecycleListeners) {
            try {
                callback(listener)
            } catch (ex: Exception) {
                WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
            }
        }
    }

    fun getChattingContextFromParam(param: HookParam): Any {
        val holder = param.thisObject!!
        val field = chattingContextFieldCache.getOrPut(holder.javaClass) {
            holder.reflekt()
                .firstField { type = WeMessageApi.classChattingContext.clazz }
                .self.makeAccessible()
        }
        return field.get(holder)!!
    }

    private val chattingContextFieldCache = ConcurrentHashMap<Class<*>, Field>()

    /** Returns every (view, message) pair currently bound whose message satisfies [matcher]. */
    fun findBoundViews(matcher: (MessageInfo) -> Boolean): List<Pair<View, MessageInfo>> {
        synchronized(currentBindings) {
            return currentBindings.entries
                .filter { matcher(it.value) }
                .map { it.key to it.value }
        }
    }

    fun getBoundMessage(view: View): MessageInfo? =
        synchronized(currentBindings) { currentBindings[view] }

    fun getMsgInfoFromParam(param: HookParam): MessageInfo {
        val item = param.args[1]
        // The bind hook already resolved this item; listeners share that result.
        synchronized(bindLock) {
            if (item != null && item === bindItem) bindMessage?.let { return it }
        }

        val current = item?.let { findMessageInfo(it) }
        if (current != null) return current

        val holder = param.thisObject!!
        val adapterField = adapterFieldCache.getOrPut(holder.javaClass) {
            holder.reflekt()
                .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
                .self.makeAccessible()
        }
        val chattingDataAdapter = adapterField.get(holder)!!
        val getItem = adapterGetItemCache.getOrPut(chattingDataAdapter.javaClass) {
            chattingDataAdapter.reflekt()
                .firstMethod { name = "getItem" }
                .self.makeAccessible()
        }
        val msgId = param.args[2] as Int
        val msgInfo = getItem.invoke(chattingDataAdapter, msgId)!!
        return MessageInfo(msgInfo)
    }

    private val adapterFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val adapterGetItemCache = ConcurrentHashMap<Class<*>, Method>()

    /**
     * The adapter item class is stable, so the field chain that reaches the message model only
     * needs to be resolved once. An empty list caches "no such field here" so repeated binds
     * fall back to the adapter without another reflective scan.
     */
    private val msgInfoPathCache = ConcurrentHashMap<Class<*>, List<Field>>()

    private fun findMessageInfo(value: Any): MessageInfo? {
        val clazz = value.javaClass
        if (clazz == WeMessageApi.classMsgInfo.clazz) {
            return MessageInfo(value)
        }

        val cached = msgInfoPathCache[clazz]
        if (cached != null) {
            if (cached.isEmpty()) return null
            followMessageInfoPath(value, cached)?.let { return MessageInfo(it) }
        }

        val path = searchMessageInfoPath(value, 2) ?: run {
            msgInfoPathCache[clazz] = emptyList()
            return null
        }
        msgInfoPathCache[clazz] = path
        return MessageInfo(followMessageInfoPath(value, path)!!)
    }

    private fun searchMessageInfoPath(value: Any, depth: Int): List<Field>? {
        if (depth == 0) return null

        for (field in value.reflekt().fields { superclass() }) {
            val nested = runCatching { field.get() }.getOrNull() ?: continue
            if (nested.javaClass == WeMessageApi.classMsgInfo.clazz) {
                return listOf(field.self.makeAccessible())
            }
            searchMessageInfoPath(nested, depth - 1)
                ?.let { return listOf(field.self.makeAccessible()) + it }
        }
        return null
    }

    private fun followMessageInfoPath(value: Any, path: List<Field>): Any? {
        var current: Any = value
        for (field in path) {
            current = field.get(current) ?: return null
        }
        return current.takeIf { it.javaClass == WeMessageApi.classMsgInfo.clazz }
    }

    /**
     * Resolves the holder's content view once per holder class instead of rebuilding the
     * field spec (and rescanning the hierarchy) on every message bind.
     */
    private val holderViewFieldCache = ConcurrentHashMap<Class<*>, Field>()

    private fun holderView(holder: Any): View {
        val field = holderViewFieldCache.getOrPut(holder.javaClass) {
            holder.reflekt().firstField {
                type = View::class
                superclass()
            }.self.makeAccessible()
        }
        return field.get(holder) as View
    }
}
