package com.xiao.feng.activity.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xiao.feng.R
import java.util.Locale

/**
 * HSV color picker with a saturation/value panel, hue and alpha sliders, and a hex field.
 * Colors are emitted as `RRGGBB`, or `AARRGGBB` when alpha is below 255.
 */
@Composable
fun ThemeColorPickerDialog(
    initialColor: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember(initialColor) { parsePickerColor(initialColor) }
    val initialHsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(initial.alpha) }
    var hexText by remember { mutableStateOf(hexOf(initial)) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    val color = Color.hsv(hue, saturation, brightness, alpha)

    fun emitHex() {
        hexText = hexOf(color)
    }

    fun applyHex(text: String) {
        hexText = text
        val clean = text.trim().removePrefix("#")
        if (clean.length != 6 && clean.length != 8) return
        val parsed = runCatching {
            if (clean.length == 6) Color(0xFF000000L or clean.toLong(16)) else Color(clean.toLong(16))
        }.getOrNull() ?: return
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(parsed.toArgb(), hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        alpha = parsed.alpha
    }

    fun updateSv(offset: Offset) {
        if (panelSize.width == 0 || panelSize.height == 0) return
        saturation = (offset.x / panelSize.width).coerceIn(0f, 1f)
        brightness = (1f - offset.y / panelSize.height).coerceIn(0f, 1f)
        emitHex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_studio_pick_color)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = ::applyHex,
                        label = { Text(stringResource(R.string.theme_studio_color_value)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .onSizeChanged { panelSize = it }
                        .pointerInput(Unit) {
                            detectTapGestures { offset -> updateSv(offset) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                updateSv(change.position)
                            }
                        },
                ) {
                    drawRect(Color.White)
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))),
                    )
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val x = saturation * size.width
                    val y = (1f - brightness) * size.height
                    drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, y))
                    drawCircle(Color.Black, radius = 6.dp.toPx(), center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }

                Text(stringResource(R.string.theme_studio_hue), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    onValueChangeFinished = ::emitHex,
                    valueRange = 0f..360f,
                )

                Text(stringResource(R.string.theme_studio_alpha), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = alpha,
                    onValueChange = { alpha = it },
                    onValueChangeFinished = ::emitHex,
                    valueRange = 0f..1f,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hexOf(color)) }) {
                Text(stringResource(R.string.dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )

}

private fun hexOf(color: Color): String {
    val argb = color.toArgb()
    val alpha = (argb ushr 24) and 0xFF
    return if (alpha == 0xFF) {
        String.format(Locale.US, "%06X", argb and 0xFFFFFF)
    } else {
        String.format(Locale.US, "%08X", argb)
    }
}

private fun parsePickerColor(value: String): Color {
    val clean = value.trim().removePrefix("#")
    return runCatching {
        when (clean.length) {
            6 -> Color(0xFF000000L or clean.toLong(16))
            8 -> Color(clean.toLong(16))
            else -> Color.Black
        }
    }.getOrDefault(Color.Black)
}
