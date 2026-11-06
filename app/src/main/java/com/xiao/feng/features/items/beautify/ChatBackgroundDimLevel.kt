package com.xiao.feng.features.items.beautify

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import com.xiao.feng.R
import com.xiao.feng.data.KvStore.prefOption
import com.xiao.feng.featurepoints.abc.IResolveDex
import com.xiao.feng.featurepoints.dsl.dexMethod
import com.xiao.feng.features.core.ClickableFeature
import com.xiao.feng.features.core.FeatureCategoryIds
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.TextButton
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.utils.showComposeDialog

object ChatBackgroundDimLevel : ClickableFeature(), IResolveDex {

    override val technicalId = "聊天背景压暗程度"
    override val nameRes = R.string.feature_chat_background_dim_level_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_chat_background_dim_level_description

    private val methodSetChatBackground by dexMethod()

    private var dimLevel by prefOption("chat_background_dim_level", 60)

    private const val HOST_DIM_OVERLAY_COLOR = 0x99000000.toInt()

    /** Marks the host's dim view after its layout color has been replaced by our own. */
    private const val DIM_OVERLAY_TAG = 0x7E000020

    override fun onEnable() {
        methodSetChatBackground.hookAfter {
            applyDimLevel(thisObject!!)
        }
    }

    /**
     * The chat background component keeps a match-parent [ImageView] whose background is the
     * wallpaper dim layer (#99000000 in the host layout). Reusing that layer keeps the dimming
     * confined to the wallpaper instead of covering the message list, which would happen if a
     * new overlay were attached to an unknown ancestor.
     */
    private fun applyDimLevel(component: Any) {
        component.reflekt().fields { type = ImageView::class }.forEach { field ->
            val view = field.get() as? ImageView ?: return@forEach
            val hostOverlay = (view.background as? ColorDrawable)?.color == HOST_DIM_OVERLAY_COLOR
            if (!hostOverlay && view.getTag(DIM_OVERLAY_TAG) != true) return@forEach

            if (hostOverlay) view.background = ColorDrawable(Color.BLACK)
            view.setTag(DIM_OVERLAY_TAG, true)
            view.alpha = dimLevel / 100f
            view.visibility = if (dimLevel == 0) View.INVISIBLE else View.VISIBLE
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var dimLevelInput by remember { mutableIntStateOf(dimLevel) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_chat_background_dim_level_name)) },
                text = {
                    SegmentedColumn(
                        contentPadding = PaddingValues(0.dp),
                        title = stringResource(R.string.chat_background_dim_title),
                    ) {
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.chat_background_dim_level),
                                    description = stringResource(R.string.chat_background_dim_level_description),
                                    value = dimLevelInput,
                                    startInt = 0,
                                    endInt = 100,
                                    stepSize = 1,
                                    valueSuffix = "%",
                                    onValueChange = {
                                        dimLevelInput = it
                                        dimLevel = it
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
