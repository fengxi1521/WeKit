package com.xiao.feng.activity.theme

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.xiao.feng.R
import com.xiao.feng.activity.settings.CONTENT_BOTTOM_INSET
import com.xiao.feng.activity.settings.M3ListScaffold
import com.xiao.feng.features.items.beautify.Themes
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.ExpressiveBackButton
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn

@Composable
fun ThemeStudioHomeScreen(
    revision: Int,
    onBack: () -> Unit,
    onOpenTheme: (String) -> Unit,
) {
    val context = LocalContext.current
    val localizedContext = LocalXiaoFengLocalizedContext.current
    val themes = remember(revision) { Themes.availableThemes() }
    var selectedId by remember(revision) { mutableStateOf(Themes.selectedThemeId()) }
    var showCreate by remember { mutableStateOf(false) }
    val currentName = when (val id = selectedId) {
        ThemeStudioRepository.NO_THEME_ID -> stringResource(R.string.beautify_theme_none)
        else -> themes.firstOrNull { it.id == id }?.name ?: stringResource(R.string.beautify_theme_none)
    }

    if (showCreate) {
        CreateThemeDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, author, version, description ->
                val id = ThemeStudioRepository.create(name, author, version, description)
                showCreate = false
                Toast.makeText(
                    context,
                    localizedContext.getString(R.string.theme_studio_created),
                    Toast.LENGTH_SHORT,
                ).show()
                onOpenTheme(id)
            },
        )
    }

    M3ListScaffold(
        title = stringResource(R.string.theme_studio_title),
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item {
            StudioHero(themeCount = themes.size, currentName = currentName)
        }
        item {
            Spacer(Modifier.height(8.dp))
            SegmentedColumn(
                title = stringResource(R.string.theme_studio_new_group),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                item(key = "create") {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Add,
                        title = stringResource(R.string.theme_studio_new),
                        description = stringResource(R.string.theme_studio_new_desc),
                        onClick = { showCreate = true },
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            SegmentedColumn(
                title = stringResource(R.string.theme_studio_list),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                item(key = ThemeStudioRepository.NO_THEME_ID) {
                    RadioButtonWidget(
                        title = stringResource(R.string.beautify_theme_none),
                        description = stringResource(R.string.beautify_theme_none_summary),
                        icon = MaterialSymbols.Outlined.Palette,
                        selected = selectedId == ThemeStudioRepository.NO_THEME_ID,
                        onClick = {
                            selectedId = ThemeStudioRepository.NO_THEME_ID
                            Themes.selectTheme(ThemeStudioRepository.NO_THEME_ID)
                        },
                    )
                }
                themes.forEach { theme ->
                    item(key = theme.id) {
                        RadioButtonWidget(
                            title = theme.name.ifBlank { theme.id },
                            description = buildString {
                                append(stringResource(R.string.beautify_theme_author, theme.author.ifBlank { "—" }))
                                if (theme.version.isNotBlank()) {
                                    append(stringResource(R.string.beautify_theme_version, theme.version))
                                }
                            },
                            icon = MaterialSymbols.Outlined.Check_circle,
                            selected = selectedId == theme.id,
                            trailingDivider = true,
                            onClick = { onOpenTheme(theme.id) },
                            onSelect = {
                                selectedId = theme.id
                                Themes.selectTheme(theme.id)
                                Toast.makeText(
                                    context,
                                    localizedContext.getString(R.string.beautify_theme_saved),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.theme_studio_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }
}

@Composable
private fun StudioHero(themeCount: Int, currentName: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
                .size(96.dp)
                .clip(RoundedCornerShape(48.dp))
                .background(scheme.onPrimary.copy(alpha = 0.10f)),
        )
        Column(Modifier.padding(22.dp)) {
            Text(
                text = stringResource(R.string.theme_studio_title),
                color = scheme.onPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.theme_studio_desc, themeCount),
                color = scheme.onPrimary.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.onPrimary.copy(alpha = 0.16f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check_circle,
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.theme_studio_current, currentName),
                            color = scheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateThemeDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, author: String, version: String, description: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("1.0") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_studio_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.theme_studio_name)) },
                    placeholder = { Text(stringResource(R.string.theme_studio_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.theme_studio_author)) },
                    placeholder = { Text(stringResource(R.string.theme_studio_author_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text(stringResource(R.string.theme_studio_version)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.theme_studio_description)) },
                    placeholder = { Text(stringResource(R.string.theme_studio_description_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), author.trim(), version.trim(), description.trim()) },
            ) {
                Text(stringResource(R.string.theme_studio_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
