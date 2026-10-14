package com.xiao.feng.features.items.scripting_python

import android.content.Intent
import androidx.activity.ComponentActivity
import com.xiao.feng.R
import com.xiao.feng.activity.scripting_python.PythonScriptsSettingsActivity
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.items.scripting_python.plugin.PythonPluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object PythonScriptingFeature : ClickableFeature() {
    override val technicalId = "Python 插件引擎"
    override val nameRes = R.string.feature_python_scripting_name
    override val descriptionRes = R.string.feature_python_scripting_description
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_PYTHON)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onEnable() {
        scope.launch {
            PythonPluginManager.discover()
            PythonPluginManager.activateDesired()
        }
    }

    override fun onDisable() {
        scope.launch { PythonPluginManager.deactivateAll() }
    }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, PythonScriptsSettingsActivity::class.java))
    }
}
