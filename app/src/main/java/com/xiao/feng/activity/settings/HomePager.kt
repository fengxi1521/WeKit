package com.xiao.feng.activity.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Code
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.xiao.feng.BuildConfig
import com.xiao.feng.R
import com.xiao.feng.activity.openLsposedManager
import com.xiao.feng.activity.openRootManager
import com.xiao.feng.loader.entry.zygisk.ZygiskLoaderService
import com.xiao.feng.loader.startup.StartupInfo
import com.xiao.feng.ui.content.m3.SectionLabel
import com.xiao.feng.ui.content.m3.XfMasthead
import com.xiao.feng.ui.content.m3.XfPanel
import com.xiao.feng.ui.content.m3.XfSpecRow
import com.xiao.feng.ui.content.m3.XfStatTile
import com.xiao.feng.utils.HostInfo
import com.xiao.feng.utils.formatEpoch


// ---------------------------------------------------------------------------
//  Page 0 — Home dashboard
// ---------------------------------------------------------------------------

@Composable
fun HomePager(onOpenFeatures: () -> Unit = {}) {
    M3ListScaffold(
        title = stringResource(R.string.app_name),
        eyebrow = "XIAOFENG · ${BuildConfig.VERSION_NAME}",
        subtitle = stringResource(R.string.home_brand_tagline),
    ) {
        item { ModuleStrip() }
        item { Spacer(Modifier.height(18.dp)) }

        item {
            SectionLabel(
                text = stringResource(R.string.home_overview_title),
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 0.dp),
            )
        }
        item {
            DashboardStats(
                onOpenFeatures = onOpenFeatures,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        item { Spacer(Modifier.height(10.dp)) }
        item {
            SectionLabel(
                text = stringResource(R.string.home_device_info_title),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item { DeviceInformation(modifier = Modifier.padding(horizontal = 20.dp)) }

        item { Spacer(Modifier.height(10.dp)) }
        item {
            SectionLabel(
                text = stringResource(R.string.home_learn_more_title),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item { LearnMore(modifier = Modifier.padding(horizontal = 20.dp)) }
        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

/**
 * Compact runtime strip under the masthead: loader, hook bridge and host version on one panel.
 * It replaces the old status card that repeated the module name a second time.
 */
@Composable
private fun ModuleStrip() {
    val context = LocalContext.current
    val activity = LocalComponentActivity.current
    var showNoRootManager by remember { mutableStateOf(false) }
    val hookBridgeName = StartupInfo.hookBridge?.hookBridgeName
        ?: stringResource(R.string.common_not_provided)

    XfPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable {
                if (StartupInfo.loaderService is ZygiskLoaderService) {
                    showNoRootManager = !openRootManager(context)
                } else {
                    openLsposedManager(activity)
                }
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_module_activated),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "$hookBridgeName · ${StartupInfo.loaderService.loaderName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            XfSpecRow(
                label = stringResource(R.string.home_wechat_version),
                value = "${HostInfo.versionName} · ${HostInfo.versionCode}",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
        }
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

/**
 * Asymmetric bento: two stacked counters on the left, one taller cell on the right. It reads as a
 * dashboard grid instead of the old three identical tiles.
 */
@Composable
private fun DashboardStats(
    onOpenFeatures: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision = FeatureCategoryState.revision
    val enabledCount = remember(revision) { FeatureCategoryState.enabledItems().size }
    val newCount = remember(revision) { FeatureCategoryState.newItems.size }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            XfStatTile(
                value = enabledCount.toString(),
                label = stringResource(R.string.home_stat_enabled),
                icon = MaterialSymbols.Outlined.Check_circle,
                height = 98.dp,
                onClick = onOpenFeatures,
                modifier = Modifier.fillMaxWidth(),
            )
            XfStatTile(
                value = newCount.toString(),
                label = stringResource(R.string.home_stat_new),
                icon = MaterialSymbols.Outlined.Wand_stars,
                height = 98.dp,
                onClick = onOpenFeatures,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        XfStatTile(
            value = FEATURE_CATEGORIES.size.toString(),
            label = stringResource(R.string.home_stat_categories),
            icon = MaterialSymbols.Outlined.Chat,
            height = 206.dp,
            onClick = onOpenFeatures,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DeviceInformation(modifier: Modifier = Modifier) {
    val loaderName = StartupInfo.loaderService.loaderName

    XfPanel(modifier = modifier.fillMaxWidth()) {
        Column {
            XfSpecRow(
                label = stringResource(R.string.home_module_version),
                value = "${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            SpecDivider()
            XfSpecRow(
                label = stringResource(R.string.home_build_time),
                value = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            SpecDivider()
            XfSpecRow(
                label = stringResource(R.string.home_device_model),
                value = "${Build.MANUFACTURER} ${Build.MODEL}",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            SpecDivider()
            XfSpecRow(
                label = stringResource(R.string.home_android_version),
                value = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
            SpecDivider()
            XfSpecRow(
                label = stringResource(R.string.home_loading_environment),
                value = "$loaderName · ${StartupInfo.hookBridge?.hookBridgeName ?: stringResource(R.string.common_not_provided)}",
                modifier = Modifier.padding(horizontal = 0.dp),
            )
        }
    }
}

@Composable
private fun SpecDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
    )
}

@Composable
private fun LearnMore(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    XfPanel(
        modifier = modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri("https://docs.xfeng.dev") },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_learn_more_item_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.home_learn_more_item_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "docs.xfeng.dev",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}
