package com.xiao.feng.activity.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.xiao.feng.BuildConfig
import com.xiao.feng.R
import com.xiao.feng.activity.openLsposedManager
import com.xiao.feng.activity.openRootManager
import com.xiao.feng.loader.entry.zygisk.ZygiskLoaderService
import com.xiao.feng.loader.startup.StartupInfo
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.formatEpoch


// ---------------------------------------------------------------------------
//  Page 0 — Home
// ---------------------------------------------------------------------------

@Composable
fun HomePager() {
    M3ListScaffold(title = stringResource(R.string.app_name)) {
        item {
            Column(
                modifier = Modifier.padding(top = 12.dp),
            ) {
                BrandHero()
                Spacer(Modifier.height(16.dp))
                StatusCard()
                DeviceInformation()
                LearnMore()
                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
        }
    }
}

/**
 * Brand header: a gradient card that gives XiaoFeng its own visual identity instead of
 * opening straight into a plain settings list.
 */
@Composable
private fun BrandHero() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .size(88.dp)
                .background(scheme.onPrimary.copy(alpha = 0.12f), CircleShape),
        )
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_brand_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimary.copy(alpha = 0.82f),
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeroBadge(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                Spacer(Modifier.width(8.dp))
                HeroBadge(text = StartupInfo.loaderService.loaderName)
            }
        }
    }
}

@Composable
private fun HeroBadge(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(scheme.onPrimary.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusCard() {
    val context = LocalContext.current
    val activity = LocalComponentActivity.current
    var showNoRootManager by remember { mutableStateOf(false) }
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val statusTitle = stringResource(R.string.home_module_activated)
    val hookBridgeName = StartupInfo.hookBridge?.hookBridgeName
        ?: stringResource(R.string.common_not_provided)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.large,
        onClick = {
            if (StartupInfo.loaderService is ZygiskLoaderService) {
                showNoRootManager = !openRootManager(context)
            } else {
                openLsposedManager(activity)
            }
        },
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Check_circle,
                    contentDescription = statusTitle,
                )
            },
            supportingContent = {
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                StatusTag(
                    label = hookBridgeName,
                    backgroundColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = contentColor,
                leadingContentColor = contentColor,
                trailingContentColor = contentColor,
                supportingContentColor = contentColor.copy(alpha = 0.7f),
            ),
            content = {
                Text(
                    text = statusTitle,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
            },
        )
    }

    if (showNoRootManager) {
        AlertDialog(
            onDismissRequest = { showNoRootManager = false },
            title = { Text(stringResource(R.string.manager_launch_failed_title)) },
            text = { Text(stringResource(R.string.manager_launch_no_root_manager)) },
            confirmButton = {
                TextButton(onClick = { showNoRootManager = false }) {
                    Text(stringResource(R.string.dialog_confirm))
                }
            },
        )
    }
}

@Composable
private fun StatusTag(label: String, backgroundColor: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(4.dp),
            )
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmallEmphasized,
            color = contentColor,
        )
    }
}

@Composable
private fun DeviceInformation() {
    val loaderName = StartupInfo.loaderService.loaderName
    SegmentedColumn(title = stringResource(R.string.home_device_info_title)) {
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_wechat_version),
                description = stringResource(R.string.home_version_value, HostInfo.versionName, HostInfo.versionCode),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_module_version),
                description = stringResource(
                    R.string.home_version_value,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_build_time),
                description = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_device_model),
                description = stringResource(R.string.home_device_model_value, Build.MANUFACTURER, Build.MODEL),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_android_version),
                description = stringResource(
                    R.string.home_android_version_value,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT,
                ),
            )
        }
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_loading_environment),
                description = stringResource(
                    R.string.home_loading_environment_value,
                    loaderName,
                    StartupInfo.hookBridge?.hookBridgeName ?: stringResource(R.string.common_not_provided),
                ),
            )
        }
    }
}

@Composable
private fun LearnMore() {
    val uriHandler = LocalUriHandler.current
    SegmentedColumn(title = stringResource(R.string.home_learn_more_title)) {
        item {
            BaseWidget(
                iconPlaceholder = false,
                title = stringResource(R.string.home_learn_more_item_title),
                description = stringResource(R.string.home_learn_more_item_summary),
                onClick = { uriHandler.openUri("https://docs.xfeng.dev") },
            )
        }
    }
}
