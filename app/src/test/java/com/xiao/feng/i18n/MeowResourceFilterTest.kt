package com.xiao.feng.i18n

import com.xiao.feng.R
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeowResourceFilterTest {
    @Test
    fun acceptsOnlyXiaoFengResourcePackageIds() {
        assertTrue(MeowResourceFilter.isXiaoFengResource(R.string.settings_title))
        assertFalse(MeowResourceFilter.isXiaoFengResource(0x7f010001))
        assertFalse(MeowResourceFilter.isXiaoFengResource(android.R.string.ok))
    }
}
