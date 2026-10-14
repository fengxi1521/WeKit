package com.xiao.feng.features.items.moments

import android.content.Context
import com.xiao.feng.R
import com.xiao.feng.i18n.LocalXiaoFengLocalizedContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.features.api.ui.WeMomentsContextMenuApi
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.features.core.SwitchFeature
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.DefaultColumn
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.PlaceholderChips
import com.xiao.feng.ui.utils.EditIcon
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.WeLogger
import com.xiao.feng.data.XiaoFengDatabase
import com.xiao.feng.data.entity.MomentCustomDetailEntity
import com.xiao.feng.data.JsonDataMigration
import com.xiao.feng.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object CustomDetails : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    override val technicalId = "自定义底部详细信息"
    override val nameRes = R.string.feature_custom_details_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_custom_details_description

    private const val TAG = "CustomDetails"

    private val PLACEHOLDERS = listOf(
        $$"$originalText",
        $$"$time",
        $$"$type",
        $$"$snsId",
        $$"$userName"
    )

    private val customDetails = ConcurrentHashMap<String, String>()

    override fun onEnable() {
        runBlocking(Dispatchers.IO) {
            JsonDataMigration.requireCompleted("moments", "custom_bottom_details")
            val saved = XiaoFengDatabase.instance.simpleStructuredDao().getCustomDetails()
            customDetails.clear()
            customDetails.putAll(saved.associate { it.snsId to it.text })
        }
        WeMomentsContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777017,
                localizedMomentsString(R.string.moments_custom_details_menu),
                EditIcon,
                { _, _ -> true }
            ) click@{ moment ->
                val snsId = resolveSnsId(moment.snsInfo)
                if (snsId == null) {
                    showToast(moment.activity.localizedMomentsString(R.string.moments_sns_id_not_found))
                    return@click
                }
                showEditor(moment.activity, snsId)
            }
        )
    }

    fun getCustomText(snsId: Long): String? = customDetails[snsId.toString()]

    private fun showEditor(context: Context, snsId: Long) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(TextFieldValue(getCustomText(snsId).orEmpty())) }
            var isFocused by remember { mutableStateOf(false) }
            var saving by remember { mutableStateOf(false) }
            var saveFailed by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val localizedContext by rememberUpdatedState(LocalXiaoFengLocalizedContext.current)

            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_custom_details_title)) },
                text = {
                    DefaultColumn {
                        Text(stringResource(R.string.moments_custom_details_empty_hint))
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            enabled = !saving,
                            label = { Text(stringResource(R.string.moments_custom_details_content)) },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text(stringResource(R.string.moments_custom_details_insert_placeholder))

                        if (saving) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.structured_storage_saving))
                        } else {
                            PlaceholderChips(
                                placeholders = PLACEHOLDERS,
                                value = textInput,
                                isFieldFocused = isFocused,
                                onValueChange = { textInput = it },
                            )
                        }
                        if (saveFailed) {
                            Text(stringResource(R.string.structured_storage_save_failed), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(enabled = !saving, onClick = {
                        saving = true
                        saveFailed = false
                        dialog.setCancelable(false)
                        scope.launch {
                            try {
                                val key = snsId.toString()
                                val text = textInput.text.trim()
                                withContext(Dispatchers.IO) {
                                    val dao = XiaoFengDatabase.instance.simpleStructuredDao()
                                    if (text.isBlank()) {
                                        dao.removeCustomDetail(key)
                                        customDetails.remove(key)
                                    } else {
                                        dao.putCustomDetail(MomentCustomDetailEntity(key, text))
                                        customDetails[key] = text
                                    }
                                }
                                showToast(
                                    localizedContext.getString(
                                        if (textInput.text.isBlank()) R.string.moments_custom_details_cleared
                                        else R.string.moments_custom_details_saved
                                    )
                                )
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                WeLogger.e(TAG, "failed to save custom bottom details", e)
                                saveFailed = true
                            } finally {
                                saving = false
                                dialog.setCancelable(true)
                            }
                        }
                    }) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            )
        }
    }

    private fun resolveSnsId(snsInfo: Any?): Long? {
        return (snsInfo?.reflekt()?.getField("field_snsId", true) as? Number)?.toLong()
    }

}
