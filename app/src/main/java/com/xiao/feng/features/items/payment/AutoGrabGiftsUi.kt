package com.xiao.feng.features.items.payment

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import com.xiao.feng.ui.content.AlertDialogContent
import com.xiao.feng.ui.content.Button
import com.xiao.feng.ui.content.m3.BaseItemContainer
import com.xiao.feng.ui.content.m3.IntNumberPickerWidget
import com.xiao.feng.ui.content.m3.SegmentedColumn
import com.xiao.feng.ui.utils.showComposeDialog

fun showAutoGrabGiftsDialog(context: ComponentActivity) {
    showComposeDialog(context) {
        var delayInput by remember { mutableIntStateOf(AutoGrabGifts.delaySeconds) }

        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_auto_grab_gifts_name)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = stringResource(R.string.auto_grab_gifts_delay_title),
                                    description = stringResource(R.string.auto_grab_gifts_delay_description),
                                    value = delayInput,
                                    startInt = 0,
                                    endInt = 30,
                                    stepSize = 1,
                                    valueSuffix = "s",
                                    onValueChange = {
                                        delayInput = it
                                        AutoGrabGifts.delaySeconds = it
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}
