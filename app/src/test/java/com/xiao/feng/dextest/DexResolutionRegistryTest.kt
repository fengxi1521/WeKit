package com.xiao.feng.dextest

import com.xiao.feng.dexkit.abc.IResolveDex
import com.xiao.feng.features.core.DexResolutionTestRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DexResolutionRegistryTest {
    @Test
    fun registryContainsOnlyLazyResolverMetadata() {
        val entries = DexResolutionTestRegistry.ITEMS
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.size, entries.map { it.className }.distinct().size)
        assertTrue(entries.any { it.className.endsWith("DisableTypingStatusUploading") })
        assertFalse(entries.any { it.className.endsWith("MomentsEditorBackOptimization") })

        entries.forEach { entry ->
            val type = Class.forName(entry.className, false, javaClass.classLoader)
            assertTrue(IResolveDex::class.java.isAssignableFrom(type))
        }
    }

    @Test
    fun pathBackedFeaturesCanInitializeOnDesktop() {
        listOf(
            "com.xiao.feng.features.items.beautify.Themes",
            "com.xiao.feng.features.items.scripting_java.JavaScriptingHook",
            "com.xiao.feng.features.items.contacts.CustomLocalFriendAvatars",
        ).forEach { className ->
            assertDoesNotThrow {
                Class.forName(className, true, javaClass.classLoader)
            }
        }
    }
}
