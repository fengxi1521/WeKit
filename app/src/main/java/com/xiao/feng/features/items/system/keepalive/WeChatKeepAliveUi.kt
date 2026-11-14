package com.xiao.feng.features.items.system.keepalive

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xiao.feng.R
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog

private fun applyIfEnabled() {
    if (WeChatKeepAlive.isEnabled) WeChatKeepAliveRuntime.start()
}

private fun openBatteryOptimizationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
fun showWeChatKeepAliveDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var foregroundService by remember { mutableStateOf(WeChatKeepAlive.foregroundService) }
        var wakeLock by remember { mutableStateOf(WeChatKeepAlive.wakeLock) }
        var watchdog by remember { mutableStateOf(WeChatKeepAlive.watchdog) }
        var networkHeartbeat by remember { mutableStateOf(WeChatKeepAlive.networkHeartbeat) }
        var rootDozeWhitelist by remember { mutableStateOf(WeChatKeepAlive.rootDozeWhitelist) }
        var rootAppOps by remember { mutableStateOf(WeChatKeepAlive.rootAppOps) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.wechat_keep_alive_settings_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.wechat_keep_alive_foreground_service),
                                description = stringResource(R.string.wechat_keep_alive_foreground_service_description),
                                checked = foregroundService,
                                onCheckedChange = {
                                    foregroundService = it
                                    WeChatKeepAlive.foregroundService = it
                                    applyIfEnabled()
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.wechat_keep_alive_wake_lock),
                                description = stringResource(R.string.wechat_keep_alive_wake_lock_description),
                                checked = wakeLock,
                                onCheckedChange = {
                                    wakeLock = it
                                    WeChatKeepAlive.wakeLock = it
                                    applyIfEnabled()
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.wechat_keep_alive_watchdog),
                                description = stringResource(R.string.wechat_keep_alive_watchdog_description),
                                checked = watchdog,
                                onCheckedChange = {
                                    watchdog = it
                                    WeChatKeepAlive.watchdog = it
                                    applyIfEnabled()
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.wechat_keep_alive_network_heartbeat),
                                description = stringResource(R.string.wechat_keep_alive_network_heartbeat_description),
                                checked = networkHeartbeat,
                                onCheckedChange = {
                                    networkHeartbeat = it
                                    WeChatKeepAlive.networkHeartbeat = it
                                    applyIfEnabled()
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.wechat_keep_alive_root_doze),
                                description = stringResource(R.string.wechat_keep_alive_root_doze_description),
                                checked = rootDozeWhitelist,
                                onCheckedChange = {
                                    rootDozeWhitelist = it
                                    WeChatKeepAlive.rootDozeWhitelist = it
                                    applyIfEnabled()
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.wechat_keep_alive_root_app_ops),
                                description = stringResource(R.string.wechat_keep_alive_root_app_ops_description),
                                checked = rootAppOps,
                                onCheckedChange = {
                                    rootAppOps = it
                                    WeChatKeepAlive.rootAppOps = it
                                    applyIfEnabled()
                                },
                            )
                        }
                    }
                    TextButton(onClick = { openBatteryOptimizationSettings(context) }) {
                        Text(stringResource(R.string.wechat_keep_alive_open_battery_settings))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }
}
