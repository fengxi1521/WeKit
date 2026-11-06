package com.xiao.feng.features.items.beautify

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import androidx.core.graphics.toColorInt
import com.tencent.mm.ui.widget.MMNeat7extView
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.features.api.core.models.MessageInfo
import com.xiao.feng.features.api.core.models.MessageType
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.BaseWidget
import com.xiao.feng.ui.content.m3.ColorPickerWidget
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.utils.findViewWhich
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam

object BubbleColorsTransparency : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "气泡颜色与透明度"
    override val nameRes = R.string.feature_bubble_colors_transparency_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_bubble_colors_transparency_description

    private const val TRANSPARENT = "#00000000"

    private var selfColor by prefOption("bubble_colors_self_color", TRANSPARENT)
    private var selfOpacity by prefOption("bubble_colors_self_opacity", 100)
    private var otherColor by prefOption("bubble_colors_other_color", TRANSPARENT)
    private var otherOpacity by prefOption("bubble_colors_other_opacity", 100)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(param: HookParam, view: View) {
        applyBubble(view, WeChatMessageViewApi.getMsgInfoFromParam(param))
    }

    @Suppress("DEPRECATION")
    private fun applyBubble(view: View, msgInfo: MessageInfo) {
        val type = msgInfo.type
        if (type == null || type.isSystem) return
        if (!(type.isText || type == MessageType.LINK || type == MessageType.GROUP_NOTE)) return

        val colorRaw = if (msgInfo.isSelfSender) selfColor else otherColor
        if (colorRaw == TRANSPARENT) return

        val opacity = if (msgInfo.isSelfSender) selfOpacity else otherOpacity
        val color = colorRaw.toColorInt() or 0xFF000000.toInt()
        val bubble = view.findViewWhich { it is MMNeat7extView } ?: return
        applyBubbleTint(bubble.background, color, opacity)
    }

    private fun refreshBoundBubbles() {
        WeChatMessageViewApi.findBoundViews { true }.forEach { (view, msgInfo) ->
            applyBubble(view, msgInfo)
        }
    }

    @Suppress("DEPRECATION")
    private fun clearBoundBubbles() {
        WeChatMessageViewApi.findBoundViews { true }.forEach { (view, msgInfo) ->
            val type = msgInfo.type
            if (type == null || type.isSystem) return@forEach
            if (!(type.isText || type == MessageType.LINK || type == MessageType.GROUP_NOTE)) return@forEach

            val bubble = view.findViewWhich { it is MMNeat7extView } ?: return@forEach
            bubble.background?.mutate()?.apply {
                colorFilter = null
                alpha = 255
            }
        }
    }

    /**
     * Tints and fades the bubble without touching its geometry: the color filter replaces the
     * pixels while keeping their alpha, so the nine-patch border and its anti-aliased edges stay
     * intact. The drawable is mutated first because WeChat shares its bubble drawables.
     */
    private fun applyBubbleTint(background: Drawable?, color: Int, opacity: Int) {
        val drawable = background?.mutate() ?: return
        drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        drawable.alpha = opacity * 255 / 100
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selfColorInput by remember { mutableStateOf(selfColor) }
            var selfOpacityInput by remember { mutableIntStateOf(selfOpacity) }
            var otherColorInput by remember { mutableStateOf(otherColor) }
            var otherOpacityInput by remember { mutableIntStateOf(otherOpacity) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_bubble_colors_transparency_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(
                            contentPadding = PaddingValues(0.dp),
                            title = stringResource(R.string.chat_bubble_self_title),
                        ) {
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_bubble_color_label),
                                    value = selfColorInput,
                                    onValueChange = {
                                        selfColorInput = it
                                        selfColor = it
                                        refreshBoundBubbles()
                                    },
                                )
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_bubble_opacity_label),
                                        value = selfOpacityInput,
                                        startInt = 0,
                                        endInt = 100,
                                        stepSize = 1,
                                        valueSuffix = "%",
                                        onValueChange = {
                                            selfOpacityInput = it
                                            selfOpacity = it
                                            refreshBoundBubbles()
                                        },
                                    )
                                }
                            }
                        }
                        SegmentedColumn(
                            contentPadding = PaddingValues(0.dp),
                            title = stringResource(R.string.chat_bubble_other_title),
                        ) {
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.chat_bubble_color_label),
                                    value = otherColorInput,
                                    onValueChange = {
                                        otherColorInput = it
                                        otherColor = it
                                        refreshBoundBubbles()
                                    },
                                )
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_bubble_opacity_label),
                                        value = otherOpacityInput,
                                        startInt = 0,
                                        endInt = 100,
                                        stepSize = 1,
                                        valueSuffix = "%",
                                        onValueChange = {
                                            otherOpacityInput = it
                                            otherOpacity = it
                                            refreshBoundBubbles()
                                        },
                                    )
                                }
                            }
                        }
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    title = stringResource(R.string.chat_bubble_reset),
                                    description = stringResource(R.string.chat_bubble_reset_description),
                                    onClick = {
                                        selfColorInput = TRANSPARENT
                                        otherColorInput = TRANSPARENT
                                        selfOpacityInput = 100
                                        otherOpacityInput = 100
                                        selfColor = TRANSPARENT
                                        otherColor = TRANSPARENT
                                        selfOpacity = 100
                                        otherOpacity = 100
                                        clearBoundBubbles()
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
