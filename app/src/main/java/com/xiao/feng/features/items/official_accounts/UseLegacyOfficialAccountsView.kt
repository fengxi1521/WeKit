package com.xiao.feng.features.items.official_accounts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import com.xiao.feng.R
import com.xiao.feng.constants.PackageNames
import com.xiao.feng.features.api.ui.WeStartActivityApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.i18n.HostLocalizedStrings
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.utils.android.showToast

object UseLegacyOfficialAccountsView : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override val technicalId = "恢复旧版公众号列表"
    override val nameRes = R.string.feature_use_legacy_official_accounts_view_name
    override val categoryIds = listOf(FeatureCategoryIds.OFFICIAL_ACCOUNTS)
    override val descriptionRes = R.string.feature_use_legacy_official_accounts_view_description

    private const val LEGACY_OFFICIAL_ACCOUNTS_UI =
        "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"

    private fun hasLegacyOfficialAccountsUI(): Boolean = LEGACY_OFFICIAL_ACCOUNTS_UI.toClassOrNull() != null

    override fun onEnable() {
        if (!hasLegacyOfficialAccountsUI()) {
            showToast(HostLocalizedStrings.get(R.string.official_accounts_legacy_ui_missing))
            applyToggle(false)
            return
        }

        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && !hasLegacyOfficialAccountsUI()) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.error)) },
                    text = { Text(stringResource(R.string.official_accounts_legacy_ui_missing)) },
                    confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                )
            }
            return false
        }

        return true
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className
        if (className == "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
            className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI"
        ) {
            WeLogger.d("UseLegacyOfficialAccountsView", "redirected $className")
            intent.component = ComponentName(HostInfo.packageName, LEGACY_OFFICIAL_ACCOUNTS_UI)
        }
    }
}
