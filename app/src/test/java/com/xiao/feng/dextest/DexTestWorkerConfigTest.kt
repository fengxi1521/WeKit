package com.xiao.feng.dextest

import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DexTestWorkerConfigTest {
    @Test
    fun parsesAllWorkerProperties() {
        val config = DexTestWorkerConfig.fromSystemProperties(properties())
        assertEquals(3040L, config.versionCode)
        assertEquals("8.0.69", config.versionName)
        assertFalse(config.isGooglePlay)
        assertNull(config.featureSelectors)
    }

    @Test
    fun parsesFeatureSelectors() {
        val properties = properties().apply {
            setProperty("xfeng.dexTest.features", "AntiReadReceipts, AntiSecMsg")
        }

        assertEquals(
            listOf("AntiReadReceipts", "AntiSecMsg"),
            DexTestWorkerConfig.fromSystemProperties(properties).featureSelectors,
        )
    }

    @Test
    fun rejectsInvalidBooleanAndNumber() {
        val booleanProperties = properties().apply { setProperty("xfeng.dexTest.isGooglePlay", "maybe") }
        assertThrows(IllegalStateException::class.java) {
            DexTestWorkerConfig.fromSystemProperties(booleanProperties)
        }
        val numberProperties = properties().apply { setProperty("xfeng.dexTest.versionCode", "not-a-number") }
        assertThrows(IllegalStateException::class.java) {
            DexTestWorkerConfig.fromSystemProperties(numberProperties)
        }
    }

    private fun properties() = Properties().apply {
        setProperty("xfeng.dexTest.apk", "/tmp/wechat.apk")
        setProperty("xfeng.dexTest.nativeLibrary", "/tmp/libdexkit.so")
        setProperty("xfeng.dexTest.report", "/tmp/report.json")
        setProperty("xfeng.dexTest.dexKitVersion", "2.2.0")
        setProperty("xfeng.dexTest.dexKitRevision", "revision")
        setProperty("xfeng.dexTest.versionCode", "3040")
        setProperty("xfeng.dexTest.versionName", "8.0.69")
        setProperty("xfeng.dexTest.buildTag", "Android_Wechat_RELEASE")
        setProperty("xfeng.dexTest.isGooglePlay", "false")
    }
}
