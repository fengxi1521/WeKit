package com.xiao.feng.ui.agent.settings

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Terminal
import com.xiao.feng.ui.content.m3.SettingsScaffold
import com.xiao.feng.R
import com.xiao.feng.agent.data.WeAgentRepository
import com.xiao.feng.agent.environment.NATIVE_ENVIRONMENT_ID
import com.xiao.feng.features.api.agent.WeAgentService
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource

@Composable
fun LinuxEnvironmentsScreen(onBack: () -> Unit, onOpen: (String?) -> Unit) {
    val environments by WeAgentService.linuxEnvironmentManager.observeEnvironments().collectAsState(initial = emptyList())
    var defaultId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(NATIVE_ENVIRONMENT_ID) }
    androidx.compose.runtime.LaunchedEffect(Unit) { defaultId = com.xiao.feng.agent.data.WeAgentSettings.defaultLinuxEnvironmentId() ?: NATIVE_ENVIRONMENT_ID }
    SettingsScaffold(title = stringResource(R.string.agent_linux_environments_title), onBack = onBack) {
        item {
            SegmentedColumn(title = stringResource(R.string.agent_linux_environments_section)) {
                environments.forEach { environment ->
                    item {
                        RadioButtonWidget(
                            icon = MaterialSymbols.Outlined.Terminal,
                            title = environment.displayName,
                            description = "${environment.type} · ${environment.workingDirectory}",
                            selected = environment.id == defaultId,
                            onClick = { onOpen(environment.id) },
                            onSelect = {
                                defaultId = environment.id
                                 WeAgentService.setDefaultLinuxEnvironment(environment.id)
                            },
                        )
                    }
                }
                item {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Add,
                        title = stringResource(R.string.agent_linux_environment_add),
                        onClick = { onOpen(null) },
                    )
                }
            }
        }
    }
}
