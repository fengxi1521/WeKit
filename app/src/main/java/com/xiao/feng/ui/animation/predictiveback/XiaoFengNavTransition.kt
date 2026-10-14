package com.xiao.feng.ui.animation.predictiveback

import com.xiao.feng.ui.utils.theme.PageTransitionAnimation
import top.yukonga.miuix.kmp.nav.transition.NavTransition

fun xiaoFengNavTransition(animation: PageTransitionAnimation): NavTransition = when (animation) {
    PageTransitionAnimation.AOSP -> AospNavTransition
    PageTransitionAnimation.MIUIX -> top.yukonga.miuix.kmp.nav.transition.NavTransitions.MiuixDefault
}
