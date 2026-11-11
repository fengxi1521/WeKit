package com.xiao.feng.features.items.beautify

import android.util.TypedValue
import android.view.View
import android.widget.TextView
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
import com.xiao.feng.features.api.ui.WeChatMessageViewApi
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.ColorPickerWidget
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.content.m3.SwitchWidget
import com.xiao.feng.ui.utils.findViewsWhich
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam
import java.util.WeakHashMap

object MessageTextAppearance : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "消息文字外观"
    override val nameRes = R.string.feature_message_text_appearance_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_text_appearance_description

    private var textSizeSp by prefOption("message_text_size_sp", 16)
    private var lineSpacingPercent by prefOption("message_line_spacing_percent", 100)
    private var textColorFollowsHost by prefOption("message_text_color_follows_host", true)
    private var textColorHex by prefOption("message_text_color", "#FF000000")

    /** Host color captured per text view so switching back to "follow host" can undo our override. */
    private val originalTextColors = WeakHashMap<TextView, Int>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    override fun onCreateView(param: HookParam, view: View) {
        applyTextAppearance(view, WeChatMessageViewApi.getMsgInfoFromParam(param))
    }

    private fun applyTextAppearance(view: View, msgInfo: MessageInfo) {
        if (msgInfo.type?.isText != true) return

        val targetSpacing = lineSpacingPercent / 100f
        view.findViewsWhich { it is MMNeat7extView }.forEach { messageText ->
            // NeatTextView keeps its real text metrics on the wrapped TextView, so styling the
            // wrapper's TextView is what actually changes the rendered message.
            val textView = (messageText as MMNeat7extView).wrappedTextView
            val targetSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                textSizeSp.toFloat(),
                textView.resources.displayMetrics,
            )
            val sizeChanged = textView.textSize != targetSizePx
            val spacingChanged = textView.lineSpacingMultiplier != targetSpacing
            if (sizeChanged) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
            }
            if (spacingChanged) {
                textView.setLineSpacing(0f, targetSpacing)
            }
            // Binds happen for every message while the list scrolls; requesting a layout when
            // nothing changed re-measures the row on every frame and is the main scroll cost here.
            if (sizeChanged || spacingChanged) {
                messageText.requestLayout()
            }
            applyTextColor(textView)
        }
    }

    private fun applyTextColor(textView: TextView) {
        if (textColorFollowsHost) {
            // The host rebinds its own color during bind; restore the captured one so turning the
            // override off takes effect immediately instead of waiting for a recycled row.
            originalTextColors.remove(textView)?.let { hostColor ->
                if (textView.currentTextColor != hostColor) textView.setTextColor(hostColor)
            }
            return
        }
        val targetColor = textColorHex.toColorInt()
        originalTextColors.getOrPut(textView) { textView.currentTextColor }
        if (textView.currentTextColor != targetColor) textView.setTextColor(targetColor)
    }

    private fun refreshBoundTextViews() {
        WeChatMessageViewApi.findBoundViews { true }.forEach { (view, msgInfo) ->
            applyTextAppearance(view, msgInfo)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var textSizeInput by remember { mutableIntStateOf(textSizeSp) }
            var lineSpacingInput by remember { mutableIntStateOf(lineSpacingPercent) }
            var followColorInput by remember { mutableStateOf(textColorFollowsHost) }
            var colorInput by remember { mutableStateOf(textColorHex) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_message_text_appearance_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(
                            contentPadding = PaddingValues(0.dp),
                            title = stringResource(R.string.chat_message_text_appearance_title),
                        ) {
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_message_text_size_title),
                                        value = textSizeInput,
                                        startInt = 12,
                                        endInt = 24,
                                        stepSize = 1,
                                        valueSuffix = "sp",
                                        onValueChange = {
                                            textSizeInput = it
                                            textSizeSp = it
                                            refreshBoundTextViews()
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.chat_message_line_spacing_title),
                                        value = lineSpacingInput,
                                        startInt = 80,
                                        endInt = 200,
                                        stepSize = 5,
                                        valueSuffix = "%",
                                        onValueChange = {
                                            lineSpacingInput = it
                                            lineSpacingPercent = it
                                            refreshBoundTextViews()
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    SwitchWidget(
                                        title = stringResource(R.string.chat_message_text_follow_color_title),
                                        checked = followColorInput,
                                        onCheckedChange = {
                                            followColorInput = it
                                            textColorFollowsHost = it
                                            refreshBoundTextViews()
                                        },
                                    )
                                }
                            }
                            if (!followColorInput) {
                                item {
                                    ColorPickerWidget(
                                        title = stringResource(R.string.chat_message_text_color_title),
                                        value = colorInput,
                                        onValueChange = {
                                            colorInput = it
                                            textColorHex = it
                                            refreshBoundTextViews()
                                        },
                                    )
                                }
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
