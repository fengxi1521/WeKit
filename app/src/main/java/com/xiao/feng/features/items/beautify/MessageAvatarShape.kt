package com.xiao.feng.features.items.beautify

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.RadioButtonWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import com.xiao.feng.utils.reflection.cachedField

object MessageAvatarShape : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "消息头像形状"
    override val nameRes = R.string.feature_avatar_shape_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_avatar_shape_description

    private const val CIRCLE = "circle"
    private const val ROUNDED = "rounded"
    private const val SQUARE = "square"

    private var shape by prefOption("message_avatar_shape", CIRCLE)
    private var cornerRadiusDp by prefOption("message_avatar_corner_radius", 8)

    private val outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            when (shape) {
                CIRCLE -> outline.setOval(0, 0, view.width, view.height)
                ROUNDED -> outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    cornerRadiusDp * view.resources.displayMetrics.density,
                )

                else -> outline.setEmpty()
            }
        }
    }

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(param: HookParam, view: View) {
        applyAvatar(view, WeChatMessageViewApi.getMsgInfoFromParam(param))
    }

    private fun applyAvatar(view: View, msgInfo: MessageInfo) {
        if (msgInfo.type?.isSystem == true) return

        val tag = view.tag ?: return
        val avatar = tag.cachedField("avatarIV").get(tag) as? View ?: return

        if (shape == SQUARE) {
            avatar.outlineProvider = null
            avatar.clipToOutline = false
        } else {
            avatar.outlineProvider = outlineProvider
            avatar.clipToOutline = true
        }
    }

    private fun refreshBoundAvatars() {
        WeChatMessageViewApi.findBoundViews { true }.forEach { (view, msgInfo) ->
            applyAvatar(view, msgInfo)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var shapeInput by remember { mutableStateOf(shape) }
            var radiusInput by remember { mutableIntStateOf(cornerRadiusDp) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_avatar_shape_name)) },
                text = {
                    SegmentedColumn(
                        contentPadding = PaddingValues(0.dp),
                        title = stringResource(R.string.chat_avatar_shape_title),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_avatar_shape_circle),
                                selected = shapeInput == CIRCLE,
                                trailingDivider = true,
                                onClick = {
                                    shapeInput = CIRCLE
                                    shape = CIRCLE
                                    refreshBoundAvatars()
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_avatar_shape_rounded),
                                selected = shapeInput == ROUNDED,
                                trailingDivider = true,
                                onClick = {
                                    shapeInput = ROUNDED
                                    shape = ROUNDED
                                    refreshBoundAvatars()
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.chat_avatar_shape_square),
                                selected = shapeInput == SQUARE,
                                onClick = {
                                    shapeInput = SQUARE
                                    shape = SQUARE
                                    refreshBoundAvatars()
                                },
                            )
                        }
                        item(animatedVisibility = shapeInput == ROUNDED) {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_avatar_shape_radius),
                                    value = radiusInput,
                                    startInt = 0,
                                    endInt = 26,
                                    stepSize = 1,
                                    valueSuffix = "dp",
                                    onValueChange = {
                                        radiusInput = it
                                        cornerRadiusDp = it
                                        refreshBoundAvatars()
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
