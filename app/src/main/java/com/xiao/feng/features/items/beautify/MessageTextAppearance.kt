package com.xiao.feng.features.items.beautify

import android.util.TypedValue
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.utils.findViewsWhich
import com.xiao.feng.ui.utils.showComposeDialog
import com.xiao.feng.utils.HookParam

object MessageTextAppearance : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    override val technicalId = "消息文字外观"
    override val nameRes = R.string.feature_message_text_appearance_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_message_text_appearance_description

    private var textSizeSp by prefOption("message_text_size_sp", 16)
    private var lineSpacingPercent by prefOption("message_line_spacing_percent", 100)

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

        view.findViewsWhich { it is MMNeat7extView }.forEach { messageText ->
            // NeatTextView keeps its real text metrics on the wrapped TextView, so styling the
            // wrapper's TextView is what actually changes the rendered message.
            val textView = (messageText as MMNeat7extView).wrappedTextView
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
            textView.setLineSpacing(0f, lineSpacingPercent / 100f)
            messageText.requestLayout()
        }
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

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_message_text_appearance_name)) },
                text = {
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
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
