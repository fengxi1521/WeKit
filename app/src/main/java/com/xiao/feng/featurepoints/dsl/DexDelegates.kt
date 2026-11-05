@file:Suppress("unused")

package com.xiao.feng.featurepoints.dsl

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.xiao.feng.featurepoints.DexMethodDescriptor
import com.xiao.feng.features.core.BaseFeature
import com.xiao.feng.utils.reflection.ClassLoaders
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private const val PLACEHOLDER_CLASS_NAME = "com.tencent.mm.ui.LauncherUI"

/**
 * 解析失败时写入的哨兵描述符，由 [DexMethodDelegate] 与 [DexConstructorDelegate] 共用。
 * 注意与 [DexFieldDelegate] 的字段哨兵区分：两者指向同一个类但形态不同（方法 vs 字段）。
 */
private const val PLACEHOLDER_METHOD_DESCRIPTOR =
    "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"

/**
 * 所有 Dex 委托的公共基类。
 *
 * [key] 仅需在单个 Feature 内唯一，但必须跨构建稳定：内置特征点表以它做字段名。
 * 因此不能包含运行时类名（R8 混淆后每次构建都会变）——`property.name` 是编译进
 * 代码的字符串常量，不受混淆影响。
 */
sealed class BaseDexDelegate(val key: String) {
    lateinit var owner: BaseFeature

    abstract val isPlaceholder: Boolean
    abstract fun getDescriptorString(): String?
    abstract fun loadDescriptor(value: String)
}

// ---------------------------------------------------------------------------
// DexClassDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 类委托 — 自动生成 Key，自动反射获取 Class。
 */
class DexClassDelegate constructor(
    key: String,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexClassDelegate> {

    private var descriptorString: String? = null
    private var cachedClass: Class<*>? = null

    val clazz: Class<*>
        get() {
            if (isPlaceholder)
                error("Class resolution has failed: $key")
            if (cachedClass == null && descriptorString != null)
                cachedClass = descriptorString!!.toClassOrNull()
            return cachedClass ?: error("Class not found for key: $key")
        }

    @Suppress("NOTHING_TO_INLINE")
    inline fun reflekt() = clazz.reflekt()

    fun setDescriptor(className: String) {
        descriptorString = className
        cachedClass = null
    }

    override val isPlaceholder
        get() = descriptorString == PLACEHOLDER_CLASS_NAME

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexClassDelegate = this
}

// ---------------------------------------------------------------------------
// DexFieldDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 字段委托 — 自动生成 Key，自动反射获取 Field。
 */
class DexFieldDelegate constructor(
    key: String,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexFieldDelegate> {

    private var descriptorString: String? = null
    private var cachedField: Field? = null

    val field: Field
        get() {
            if (isPlaceholder)
                error("Field resolution has failed: $key")
            if (cachedField == null && descriptorString != null)
                cachedField = getFieldInstance(descriptorString!!)
            return cachedField ?: error("Field not found for key: $key")
        }

    fun setDescriptor(desc: String) {
        descriptorString = desc
        cachedField = null
    }

    override val isPlaceholder
        get() = descriptorString == PLACEHOLDER_FIELD_DESCRIPTOR

    override fun getDescriptorString(): String? = descriptorString
    override fun loadDescriptor(value: String) = setDescriptor(value)

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexFieldDelegate = this

    private fun getFieldInstance(descriptor: String): Field {
        val arrow = descriptor.indexOf("->")
        val colon = descriptor.indexOf(':', arrow)
        require(arrow >= 0 && colon >= 0) { descriptor }
        val className = descriptor.substring(1, arrow - 1).replace('/', '.')
        val fieldName = descriptor.substring(arrow + 2, colon)
        var current: Class<*>? = ClassLoaders.HOST.loadClass(className)
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException(descriptor)
    }

    companion object {
        private const val PLACEHOLDER_FIELD_DESCRIPTOR =
            "Lcom/tencent/mm/ui/LauncherUI;->INSTANCE:Lcom/tencent/mm/ui/LauncherUI;"
    }
}

// ---------------------------------------------------------------------------
// DexMethodDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 方法委托 — 自动生成 Key，自动反射获取 Method。
 */
class DexMethodDelegate constructor(
    key: String,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexMethodDelegate> {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedMethod: Method? = null

    val method: Method
        get() {
            if (isPlaceholder)
                error("Method resolution has failed: $key")
            if (cachedMethod == null && descriptor != null)
                cachedMethod = descriptor!!.getMethodInstance(ClassLoaders.HOST)
            return cachedMethod ?: error("Method not found for key: $key")
        }

    @Deprecated("You shouldn't call .reflekt() on a Method", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Method")

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedMethod = null
    }

    fun setDescriptor(className: String, methodName: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, methodName, methodSign))

    override val isPlaceholder
        get() = descriptor?.descriptor == PLACEHOLDER_METHOD_DESCRIPTOR

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedMethod = null
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexMethodDelegate = this
}

// ---------------------------------------------------------------------------
// DexConstructorDelegate
// ---------------------------------------------------------------------------

/**
 * Dex 构造函数委托 — 自动生成 Key，自动反射获取 Constructor。
 */
class DexConstructorDelegate constructor(
    key: String,
) : BaseDexDelegate(key), ReadOnlyProperty<BaseFeature, DexConstructorDelegate> {

    private var descriptor: DexMethodDescriptor? = null
    private var cachedConstructor: Constructor<*>? = null

    val constructor: Constructor<*>
        get() {
            if (isPlaceholder)
                error("Constructor resolution has failed: $key")
            if (cachedConstructor == null && descriptor != null)
                cachedConstructor = descriptor!!.getConstructorInstance(ClassLoaders.HOST)
            return cachedConstructor ?: error("Constructor not found for key: $key")
        }

    override val isPlaceholder
        get() = descriptor?.descriptor == PLACEHOLDER_METHOD_DESCRIPTOR

    @Deprecated("You shouldn't call .reflekt() on a Constructor", level = DeprecationLevel.ERROR)
    fun reflekt(): Nothing = error("You shouldn't call .reflekt() on a Constructor")

    fun newInstance(vararg initArgs: Any?): Any = constructor.newInstance(*initArgs)

    fun setDescriptor(desc: DexMethodDescriptor) {
        descriptor = desc
        cachedConstructor = null
    }

    fun setDescriptor(className: String, methodSign: String) =
        setDescriptor(DexMethodDescriptor(className, "<init>", methodSign))

    override fun getDescriptorString(): String? = descriptor?.descriptor

    override fun loadDescriptor(value: String) {
        descriptor = DexMethodDescriptor(value)
        cachedConstructor = null
    }

    override fun getValue(thisRef: BaseFeature, property: KProperty<*>): DexConstructorDelegate = this
}

// ---------------------------------------------------------------------------
// 委托工厂函数 — 自动注册到父 Feature
// ---------------------------------------------------------------------------

/**
 * 创建 dexConstructor 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexConstructor(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexConstructorDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexConstructorDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexClass 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexClass(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexClassDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexClassDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexField 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexField(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexFieldDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexFieldDelegate(key).also { item.registerDexDelegate(it) }
    }

/**
 * 创建 dexMethod 委托，并将其注册到所属 Feature 的委托列表中。
 */
fun dexMethod(): PropertyDelegateProvider<BaseFeature, ReadOnlyProperty<BaseFeature, DexMethodDelegate>> =
    PropertyDelegateProvider { item, property ->
        val key = property.name
        DexMethodDelegate(key).also { item.registerDexDelegate(it) }
    }
