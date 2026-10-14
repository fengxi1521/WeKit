package com.xiao.feng.wauxv.hook

import androidx.annotation.Keep
import com.xiao.feng.loader.abc.IHookBridge

@Keep
class HookHandle(val unhook: IHookBridge.MemberUnhookHandle) {
    override fun toString(): String {
        return "HookHandle(delegate=${unhook.member})"
    }
}
