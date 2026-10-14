package com.xiao.feng.features.items.scripting_java

import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.activity.TransparentActivity
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.utils.registerBshSnapshotDecompileLaunchers

object DecompileBeanShellSnapshot : ClickableFeature() {

    override val technicalId = "反编译 BeanShell 快照"
    override val nameRes = R.string.feature_decompile_bean_shell_snapshot_name
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_JAVA)
    override val descriptionRes = R.string.feature_decompile_bean_shell_snapshot_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val selectFileLauncher = registerBshSnapshotDecompileLaunchers { finish() }
            selectFileLauncher.launch("*/*")
        }
    }
}
