package com.xiao.feng.features.items.beautify

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.ColorPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import java.util.WeakHashMap

/**
 * Recolors the sender nickname shown above group-chat message bubbles.
 *
 * The host has no dedicated color option for that label, so the color is applied after every bind.
 * The original text color and typeface are captured per view so switching back to "follow host"
 * restores the host appearance immediately instead of waiting for a recycled row.
 */
object GroupNicknameColor : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "群昵称颜色"
    override val nameRes = R.string.feature_group_nickname_color_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_nickname_color_description

    private var followsHost by prefOption("group_nickname_color_follows_host", true)
    private var colorHex by prefOption("group_nickname_color", "#FF576B95")
    private var bold by prefOption("group_nickname_color_bold", false)

    private val originalColors = WeakHashMap<TextView, Int>()
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val nicknameByView = WeakHashMap<View, TextView>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        nicknameByView.values.forEach(::restore)
        nicknameByView.clear()
    }

    override fun onCreateView(param: HookParam, view: View) {
        val nickname = WeChatMessageViewApi.getNicknameTextView(param) ?: return
        val message = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!message.isInGroupChat) {
            restore(nickname)
            return
        }
        nicknameByView[view] = nickname
        applyNickname(nickname)
    }

    private fun applyNickname(nickname: TextView) {
        if (followsHost) {
            restore(nickname)
            return
        }

        val targetColor = colorHex.toColorInt()
        originalColors.getOrPut(nickname) { nickname.currentTextColor }
        if (nickname.currentTextColor != targetColor) {
            nickname.setTextColor(targetColor)
        }

        if (bold) {
            originalTypefaces.getOrPut(nickname) { nickname.typeface }
            val targetTypeface = Typeface.create(nickname.typeface, Typeface.BOLD)
            if (nickname.typeface != targetTypeface) {
                nickname.typeface = targetTypeface
            }
        } else {
            restoreTypeface(nickname)
        }
    }

    private fun restore(nickname: TextView) {
        originalColors.remove(nickname)?.let { color ->
            if (nickname.currentTextColor != color) nickname.setTextColor(color)
        }
        restoreTypeface(nickname)
    }

    private fun restoreTypeface(nickname: TextView) {
        originalTypefaces.remove(nickname)?.let { typeface ->
            if (nickname.typeface != typeface) nickname.typeface = typeface
        }
    }

    private fun refreshBoundNicknames() {
        nicknameByView.values.forEach(::applyNickname)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var followInput by remember { mutableStateOf(followsHost) }
            var colorInput by remember { mutableStateOf(colorHex) }
            var boldInput by remember { mutableStateOf(bold) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_group_nickname_color_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_group_nickname_color_follow_title),
                                checked = followInput,
                                onCheckedChange = {
                                    followInput = it
                                    followsHost = it
                                    refreshBoundNicknames()
                                },
                            )
                        }
                        if (!followInput) {
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_group_nickname_color_color_title),
                                    value = colorInput,
                                    onValueChange = {
                                        colorInput = it
                                        colorHex = it
                                        refreshBoundNicknames()
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_group_nickname_color_bold_title),
                                    checked = boldInput,
                                    onCheckedChange = {
                                        boldInput = it
                                        bold = it
                                        refreshBoundNicknames()
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
