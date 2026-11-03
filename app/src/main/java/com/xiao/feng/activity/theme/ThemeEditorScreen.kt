package com.xiao.feng.activity.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add_photo_alternate
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Expand_less
import com.composables.icons.materialsymbols.outlined.Expand_more
import com.composables.icons.materialsymbols.outlined.Image
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Text_fields
import com.xiao.feng.R
import com.xiao.feng.activity.settings.CONTENT_BOTTOM_INSET
import com.xiao.feng.activity.settings.M3ListScaffold
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.ExpressiveBackButton
import com.xiao.feng.ui.content.m3.SectionLabel
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.TextFieldDialogWidget
import com.xiao.feng.ui.utils.theme.ThemeSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ThemeEditorScreen(themeId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val localizedContext = LocalXiaoFengLocalizedContext.current
    var draft by remember(themeId) { mutableStateOf(ThemeStudioRepository.load(themeId)) }
    val scope = rememberCoroutineScope()
    var colorPickerKey by remember { mutableStateOf<String?>(null) }
    var pendingImageSlot by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    fun setManifest(name: String = draft.name, author: String = draft.author, version: String = draft.version, description: String = draft.description) {
        val next = draft.copy(name = name, author = author, version = version, description = description)
        draft = next
        ThemeStudioRepository.writeManifest(next.id, next.name, next.author, next.version, next.description)
    }

    fun setColor(key: String, value: String) {
        val clean = value.trim().removePrefix("#").uppercase()
        val colors = draft.colors.toMutableMap()
        // Black is the picker's "unset" value: dropping the key restores WeChat's native color.
        if (clean == "000000" || clean == "FF000000") colors.remove(key) else colors[key] = clean
        val next = draft.copy(colors = colors)
        draft = next
        ThemeStudioRepository.writeColors(next.id, next.colors)
    }

    fun setString(key: String, value: String) {
        val next = draft.copy(strings = draft.strings + (key to value))
        draft = next
        ThemeStudioRepository.writeStrings(next.id, next.strings)
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val slot = pendingImageSlot
        pendingImageSlot = null
        if (uri != null && slot != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    ThemeStudioRepository.copyImage(context, themeId, slot, uri)
                }
                if (copied) {
                    draft = draft.copy(images = draft.images + slot)
                } else {
                    Toast.makeText(
                        context,
                        localizedContext.getString(R.string.theme_studio_save_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    colorPickerKey?.let { key ->
        ThemeColorPickerDialog(
            initialColor = draft.colors[key] ?: "000000",
            onConfirm = { value ->
                setColor(key, value)
                colorPickerKey = null
            },
            onDismiss = { colorPickerKey = null },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.theme_studio_delete)) },
            text = { Text(stringResource(R.string.theme_studio_delete_confirm, draft.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        ThemeStudioRepository.delete(themeId)
                        Toast.makeText(
                            context,
                            localizedContext.getString(R.string.theme_studio_deleted),
                            Toast.LENGTH_SHORT,
                        ).show()
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.theme_studio_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    M3ListScaffold(
        title = draft.name.ifBlank { stringResource(R.string.theme_studio_title) },
        navigationIcon = { ExpressiveBackButton(onClick = onBack) },
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item { EditorHero(draft) }

        item {
            Spacer(Modifier.height(10.dp))
            SectionLabel(stringResource(R.string.theme_studio_info), Modifier.padding(horizontal = 16.dp))
            SegmentedColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                item(key = "name") {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.theme_studio_name),
                        value = draft.name,
                        onValueChange = { setManifest(name = it) },
                        dialogTitle = stringResource(R.string.theme_studio_name),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        valueHint = stringResource(R.string.theme_studio_name_hint),
                    )
                }
                item(key = "author") {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.theme_studio_author),
                        value = draft.author,
                        onValueChange = { setManifest(author = it) },
                        dialogTitle = stringResource(R.string.theme_studio_author),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        valueHint = stringResource(R.string.theme_studio_author_hint),
                    )
                }
                item(key = "version") {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.theme_studio_version),
                        value = draft.version,
                        onValueChange = { setManifest(version = it) },
                        dialogTitle = stringResource(R.string.theme_studio_version),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                    )
                }
                item(key = "description") {
                    TextFieldDialogWidget(
                        title = stringResource(R.string.theme_studio_description),
                        value = draft.description,
                        onValueChange = { setManifest(description = it) },
                        dialogTitle = stringResource(R.string.theme_studio_description),
                        confirmLabel = stringResource(R.string.dialog_confirm),
                        dismissLabel = stringResource(R.string.dialog_cancel),
                        valueHint = stringResource(R.string.theme_studio_description_hint),
                        singleLine = false,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionLabel(
                text = stringResource(R.string.theme_studio_colors),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        ThemeStudioData.COLOR_GROUPS.forEach { (group, keys) ->
            item(key = "color_group_$group") {
                SectionLabel(
                    text = colorGroupLabel(group),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                SegmentedColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    keys.forEach { key ->
                        item(key = key) {
                            ColorRow(
                                colorKey = key,
                                value = draft.colors[key],
                                onClick = { colorPickerKey = key },
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionLabel(
                text = stringResource(R.string.theme_studio_strings),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SegmentedColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                ThemeStudioData.STRING_KEYS.forEach { key ->
                    item(key = key) {
                        TextFieldDialogWidget(
                            title = stringLabel(key),
                            value = draft.strings[key].orEmpty(),
                            onValueChange = { setString(key, it) },
                            dialogTitle = stringLabel(key),
                            confirmLabel = stringResource(R.string.dialog_confirm),
                            dismissLabel = stringResource(R.string.dialog_cancel),
                            valueHint = stringResource(R.string.theme_studio_string_hint),
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionLabel(
                text = stringResource(R.string.theme_studio_images),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        ThemeStudioData.IMAGE_CATEGORIES.forEach { category ->
            val slots = ThemeStudioData.IMAGE_SLOTS_BY_CATEGORY[category].orEmpty()
            val isExpanded = expandedCategories[category] == true
            val replaced = slots.count { it.path in draft.images }
            item(key = "image_category_$category") {
                SegmentedColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                    item(key = "image_header_$category") {
                        BaseWidget(
                            icon = MaterialSymbols.Outlined.Image,
                            title = imageCategoryLabel(category),
                            description = stringResource(R.string.theme_studio_image_count, replaced, slots.size),
                            onClick = { expandedCategories[category] = !isExpanded },
                            trailingContent = {
                                Icon(
                                    imageVector = if (isExpanded) MaterialSymbols.Outlined.Expand_less else MaterialSymbols.Outlined.Expand_more,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    if (isExpanded) {
                        slots.forEach { slot ->
                            item(key = "image_${slot.path}") {
                                ThemeImageRow(
                                    slot = slot,
                                    file = File(ThemeStudioRepository.themeDir(themeId), slot.path),
                                    replaced = slot.path in draft.images,
                                    onPick = {
                                        pendingImageSlot = slot.path
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                    onClear = {
                                        ThemeStudioRepository.removeImage(themeId, slot.path)
                                        draft = draft.copy(images = draft.images - slot.path)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            SegmentedColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                item(key = "delete") {
                    BaseWidget(
                        icon = MaterialSymbols.Outlined.Delete,
                        iconColor = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.theme_studio_delete),
                        description = stringResource(R.string.theme_studio_delete_desc),
                        descriptionColor = MaterialTheme.colorScheme.error,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.theme_studio_restart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
        }
    }
}

@Composable
private fun EditorHero(draft: ThemeStudioRepository.Draft) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(scheme.secondaryContainer, scheme.tertiaryContainer))),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = draft.name.ifBlank { stringResource(R.string.theme_studio_untitled) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = scheme.onSecondaryContainer,
            )
            if (draft.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = draft.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSecondaryContainer.copy(alpha = 0.78f),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill(stringResource(R.string.theme_studio_color_count, draft.colors.size))
                HeroPill(stringResource(R.string.theme_studio_image_count, draft.images.size, ThemeStudioData.IMAGE_SLOTS.size))
            }
        }
    }
}

@Composable
private fun HeroPill(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.onSecondaryContainer.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer)
    }
}

@Composable
private fun ColorRow(colorKey: String, value: String?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BaseWidget(
        icon = MaterialSymbols.Outlined.Palette,
        title = colorKey.substringAfterLast('.'),
        description = colorKey,
        onClick = onClick,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = value?.let(::parseThemeColor)
                if (preview == null) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, scheme.outline, RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(preview),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = value ?: stringResource(R.string.theme_studio_default),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun ThemeImageRow(
    slot: ThemeStudioData.ImageSlot,
    file: File,
    replaced: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    BaseWidget(
        icon = MaterialSymbols.Outlined.Add_photo_alternate,
        title = slot.fileName,
        description = slot.path,
        onClick = onPick,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (replaced) {
                    ThemeThumbnail(file, Modifier.size(40.dp))
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Close,
                        contentDescription = stringResource(R.string.theme_studio_image_clear),
                        tint = scheme.error,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable(onClick = onClear),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.theme_studio_image_none),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun ThemeThumbnail(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun colorGroupLabel(group: String): String = when (group) {
    "chat.actionbar" -> stringResource(R.string.theme_group_chat_actionbar)
    "chat.input" -> stringResource(R.string.theme_group_chat_input)
    "chat.long_press_menu" -> stringResource(R.string.theme_group_chat_long_press_menu)
    "chat.red_packet" -> stringResource(R.string.theme_group_chat_red_packet)
    "chat.history_tongue" -> stringResource(R.string.theme_group_chat_history_tongue)
    "chat.text_bubble" -> stringResource(R.string.theme_group_chat_text_bubble)
    "chat.file_bubble" -> stringResource(R.string.theme_group_chat_file_bubble)
    "chat.red_packet_bubble" -> stringResource(R.string.theme_group_chat_red_packet_bubble)
    "chat.transfer_bubble" -> stringResource(R.string.theme_group_chat_transfer_bubble)
    "chat.link_text" -> stringResource(R.string.theme_group_chat_link_text)
    "chat.nickname_text" -> stringResource(R.string.theme_group_chat_nickname_text)
    "chat.tips_text" -> stringResource(R.string.theme_group_chat_tips_text)
    "chat.plus_panel" -> stringResource(R.string.theme_group_chat_plus_panel)
    "home.actionbar" -> stringResource(R.string.theme_group_home_actionbar)
    "home.tabs" -> stringResource(R.string.theme_group_home_tabs)
    "home.item" -> stringResource(R.string.theme_group_home_item)
    "home.conversation_item" -> stringResource(R.string.theme_group_home_conversation_item)
    "home.taskbar" -> stringResource(R.string.theme_group_home_taskbar)
    "settings.actionbar" -> stringResource(R.string.theme_group_settings_actionbar)
    "settings.switch_thumb" -> stringResource(R.string.theme_group_settings_switch_thumb)
    else -> group
}

@Composable
private fun imageCategoryLabel(category: String): String = when (category) {
    "splash" -> stringResource(R.string.theme_category_splash)
    "home" -> stringResource(R.string.theme_category_home)
    "chat" -> stringResource(R.string.theme_category_chat)
    "plus" -> stringResource(R.string.theme_category_plus)
    "settings" -> stringResource(R.string.theme_category_settings)
    else -> category
}

@Composable
private fun stringLabel(key: String): String = when (key) {
    "home.tabs.wechat_title" -> stringResource(R.string.theme_label_home_tabs_wechat_title)
    "home.tabs.contact_title" -> stringResource(R.string.theme_label_home_tabs_contact_title)
    "home.tabs.discovery_title" -> stringResource(R.string.theme_label_home_tabs_discovery_title)
    "home.tabs.me_title" -> stringResource(R.string.theme_label_home_tabs_me_title)
    "chat.input.hint" -> stringResource(R.string.theme_label_chat_input_hint)
    else -> key
}

private fun parseThemeColor(value: String): Color? {
    val clean = value.trim().removePrefix("#")
    return runCatching {
        when (clean.length) {
            6 -> Color(0xFF000000L or clean.toLong(16))
            8 -> Color(clean.toLong(16))
            else -> null
        }
    }.getOrNull()
}
