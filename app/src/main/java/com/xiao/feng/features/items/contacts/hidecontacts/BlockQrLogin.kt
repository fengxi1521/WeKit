package com.xiao.feng.features.items.contacts.hidecontacts

import android.app.Activity
import com.xiao.feng.R
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

/**
 * Closes WeChat's QR-code login confirmation page immediately after it is created, which blocks
 * another device from signing in through the phone. The class names are stable across the supported
 * 8.0.65–8.0.78 range; a missing class only disables this feature, never the rest of the module.
 */
object BlockQrLogin : SwitchFeature() {

    override val technicalId = "拦截扫码登录"
    override val nameRes = R.string.feature_block_qr_login_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_block_qr_login_description

    private const val TAG = "BlockQrLogin"

    private val LOGIN_UI_CLASSES = listOf(
        "com.tencent.mm.plugin.webwx.ui.ExtDeviceWXLoginUI",
        "com.tencent.mm.plugin.webwx.ui.LoginAuthUI",
    )

    override fun onEnable() {
        LOGIN_UI_CLASSES.forEach { className ->
            val clazz = runCatching { className.toClass() }.getOrElse {
                WeLogger.d(TAG, "$className not present on this host")
                return@forEach
            }
            val onCreate = runCatching {
                clazz.reflekt().firstMethod { name = "onCreate" }
            }.getOrElse {
                WeLogger.w(TAG, "failed to resolve $className.onCreate", it)
                return@forEach
            }

            onCreate.hookAfter {
                val activity = thisObject as Activity
                if (activity.isFinishing) return@hookAfter
                WeLogger.i(TAG, "blocked qr login page: ${activity.javaClass.name}")
                activity.finish()
                showToast(HostLocalizedStrings.get(R.string.block_qr_login_blocked))
            }
        }
    }
}
