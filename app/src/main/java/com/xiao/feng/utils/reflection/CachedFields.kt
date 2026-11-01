package com.xiao.feng.utils.reflection

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

private val cachedFields = ConcurrentHashMap<Pair<Class<*>, String>, Field>()

/**
 * Resolves a named field through [holder]'s class hierarchy once, then reuses the accessible
 * [Field] on later binds. Chat views and their holders are rebound constantly while scrolling,
 * so repeating the hierarchy scan on every bind is needlessly expensive.
 */
fun Any.cachedField(fieldName: String): Field = cachedFields.getOrPut(javaClass to fieldName) {
    reflekt()
        .firstField {
            name = fieldName
            superclass()
        }
        .self
        .makeAccessible()
}
