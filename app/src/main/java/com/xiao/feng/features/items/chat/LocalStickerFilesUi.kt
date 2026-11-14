package com.xiao.feng.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.xiao.feng.R
import com.xiao.feng.features.items.chat.panel.pickPanelFile
import com.xiao.feng.ui.content.IconButton
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 表情文件编辑器：添加按钮从相册挑图片，列表里可以预览和删除。
 *
 * 直接用 [paths] + [onPathsChange] 受控，方便 AI 聊天和进群欢迎语共用同一套交互。
 */
@Composable
fun LocalStickerFilesContent(
    context: Context,
    paths: Set<String>,
    onPathsChange: (Set<String>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = {
                pickPanelFile(context, arrayOf("image/*")) { name, uri, activity ->
                    CoroutineScope(Dispatchers.IO).launch {
                        val path = LocalStickerFiles.importFile(activity, uri, name)
                        withContext(Dispatchers.Main) {
                            activity.finish()
                            if (path == null) {
                                showToast(
                                    activity,
                                    context.getString(R.string.emoji_files_import_failed),
                                )
                            } else {
                                onPathsChange(paths + path)
                            }
                        }
                    }
                }
            }
        ) {
            Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
            Text(stringResource(R.string.emoji_files_add))
        }

        if (paths.isEmpty()) {
            Text(
                text = stringResource(R.string.emoji_files_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(paths.toList(), key = { _, path -> path }) { index, path ->
                    BaseItemContainer {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(stringResource(R.string.emoji_files_item, index + 1))
                                Text(
                                    text = File(path).name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onPathsChange(paths - path) }) {
                                Icon(
                                    MaterialSymbols.Outlined.Delete,
                                    contentDescription = stringResource(R.string.emoji_files_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
