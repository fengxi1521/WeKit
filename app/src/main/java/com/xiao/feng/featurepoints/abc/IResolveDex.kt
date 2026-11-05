package com.xiao.feng.featurepoints.abc

import com.xiao.feng.featurepoints.dsl.BaseDexDelegate

/**
 * 表示一个通过内置特征点表解析符号的 Feature。
 *
 * 实现类通过 [com.xiao.feng.featurepoints.dsl.dexClass] /
 * [com.xiao.feng.featurepoints.dsl.dexMethod] /
 * [com.xiao.feng.featurepoints.dsl.dexConstructor] 工厂函数声明委托属性，
 * 这些委托在构造时自动注册到 [dexDelegates]。
 */
interface IResolveDex {

    /**
     * 当前 Feature 持有的所有 Dex 委托。
     * 由 BaseFeature 维护，由委托工厂函数自动填充。
     */
    val dexDelegates: List<BaseDexDelegate>

    /**
     * 将所有委托的当前状态收集为 key → descriptor 字符串映射。
     */
    fun collectDescriptors(): Map<String, String> =
        dexDelegates.associate { it.key to (it.getDescriptorString() ?: "") }

    /**
     * 从内置表中逐个恢复委托状态。
     * 每个委托独立加载，某个 key 缺失不会影响其他委托。
     *
     * @return 表中不存在或值为空的 key 集合
     */
    fun loadFromCache(cache: Map<String, String>): Set<String> {
        val missingKeys = mutableSetOf<String>()
        for (delegate in dexDelegates) {
            val value = cache[delegate.key]
            if (!value.isNullOrEmpty()) {
                delegate.loadDescriptor(value)
            } else {
                missingKeys += delegate.key
            }
        }
        return missingKeys
    }
}
