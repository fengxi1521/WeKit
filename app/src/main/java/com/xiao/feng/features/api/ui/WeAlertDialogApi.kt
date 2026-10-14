package com.xiao.feng.features.api.ui

import android.content.Context
import android.content.DialogInterface
import com.xiao.feng.R
import com.xiao.feng.dexkit.abc.IResolveDex
import com.xiao.feng.dexkit.dsl.dexClass
import com.xiao.feng.features.core.ApiFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.reflection.BString

object WeAlertDialogApi : ApiFeature(), IResolveDex {

    override val technicalId = "对话框 API"
    override val nameRes = R.string.feature_we_alert_dialog_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_alert_dialog_api_description

    private val classMmAlert by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MMAlert")
        }
    }

    /**
     * NEVER use this API except for the TrollBan feature.
     */
    fun showAlertDialog(
        context: Context,
        content: String,
        title: String? = null,
        onClickOk: (DialogInterface) -> Unit = {},
        onClickCancel: (DialogInterface) -> Unit = {},
        okText: String = "确定",
        cancelText: String = "取消"
    ) {
        classMmAlert.reflekt()
            .firstMethod {
                parameters(Context::class, BString, BString, BString, BString, DialogInterface.OnClickListener::class, DialogInterface.OnClickListener::class)
            }.invokeStatic(
                context, content, title ?: "", okText, cancelText,
                DialogInterface.OnClickListener { di, _ -> onClickOk(di) },
                DialogInterface.OnClickListener { di, _ -> onClickCancel(di) })
    }
}
