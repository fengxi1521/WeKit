@file:Suppress("NOTHING_TO_INLINE")

package com.xiao.feng.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.pow

fun formatBytesSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()

    // Format to 2 decimal places
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return "%.2f %s".format(value, units[digitGroups])
}

inline fun formatEpoch(epochMs: Long, includeDate: Boolean = false): String {
    return formatEpoch(epochMs, if (includeDate) "yyyy/MM/dd HH:mm:ss" else "HH:mm:ss")
}

private val formatterZone: ZoneId = ZoneId.of("Asia/Shanghai")

/**
 * Message-time rendering runs on every list bind, so the compiled formatter is cached per pattern
 * instead of being rebuilt (and re-parsing the pattern) for every message.
 */
private val formatterCache = ConcurrentHashMap<String, DateTimeFormatter>()

fun formatEpoch(epochMs: Long, pattern: String): String {
    val formatter = formatterCache.getOrPut(pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.CHINA).withZone(formatterZone)
    }

    return formatter.format(Instant.ofEpochMilli(epochMs))
}
