@file:SuppressLint("DiscouragedPrivateApi")

package com.xiao.feng.utils.unsafe

import android.annotation.SuppressLint
import dev.ujhhgtg.reflekt.utils.makeAccessible
import sun.misc.Unsafe

val TheUnsafe by lazy {
    Unsafe::class.java
        .getDeclaredField("theUnsafe")
        .makeAccessible()
        .get(null) as Unsafe
}
