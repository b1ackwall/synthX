package com.v1per.synthx.ui.screens.developersettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
import com.v1per.synthx.ui.components.BlobBackgroundDebugState
import com.v1per.synthx.ui.components.BlobParams
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.ProductionAmbientBackgroundStyle
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.RefinedBottomSheetState
import com.v1per.synthx.ui.components.productionAmbientScrimColor
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import kotlin.math.roundToInt

@Composable
internal fun BlobBackgroundDebugSheet(
    sheetState: RefinedBottomSheetState,
    onDismissRequest: () -> Unit,
) {
    var debugState by HomeBlobDebugStateHolder.state
    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        BaseSurfaceSaturation,
        BaseSurfaceLightness,
    )

    // RefinedBottomSheet calls show() after measuring its available height.
    RefinedBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceColor.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small,
        showDragHandle = false,
        enableDragToDismiss = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Blob background", modifier = Modifier.weight(1f))
                // P5-T6: explicit escape hatch for the process-lifetime holder — without this,
                // tuning here (including a disabled blob) sticks for the rest of the process.
                TextButton(onClick = { debugState = BlobBackgroundDebugState() }) { Text("Reset") }
                TextButton(onClick = onDismissRequest) { Text("Close") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Base surface", style = MaterialTheme.typography.titleSmall)
                DebugColorSwatch(baseSurfaceColor)
            }
            DebugHueLabel(debugState.baseSurfaceHue)
            ProductionAmbientPreview(debugState.baseSurfaceHue)
            debugState.blobs.forEachIndexed { index, blob ->
                key(index) {
                    BlobDebugSection(
                        index = index,
                        blob = blob,
                    ) { update ->
                        debugState = debugState.copy(
                            blobs = debugState.blobs.mapIndexed { currentIndex, current ->
                                if (currentIndex == index) update(current) else current
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlobDebugSection(
    index: Int,
    blob: BlobParams,
    onChange: ((BlobParams) -> BlobParams) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Blob ${index + 1} ${if (expanded) "−" else "+"}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            DebugColorSwatch(Color.hsl(blob.hue, blob.saturation, blob.lightness))
            Switch(checked = blob.enabled, onCheckedChange = { enabled ->
                onChange { it.copy(enabled = enabled) }
            })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                tween(Motion.DurationNormal, easing = Motion.EasingStandard),
            ) + fadeIn(tween(Motion.DurationNormal, easing = Motion.EasingStandard)),
            exit = shrinkVertically(
                tween(Motion.DurationFast, easing = Motion.EasingAccelerate),
            ) + fadeOut(tween(Motion.DurationFast, easing = Motion.EasingAccelerate)),
        ) {
            Column {
                DebugHueLabel(blob.hue)
                DebugSlider("Saturation", blob.saturation) { value ->
                    onChange { it.copy(saturation = value) }
                }
                DebugSlider("Lightness", blob.lightness) { value ->
                    onChange { it.copy(lightness = value) }
                }
                DebugSlider("Size", blob.sizeFraction) { value ->
                    onChange { it.copy(sizeFraction = value) }
                }
                DebugSlider("Opacity", blob.opacity) { value ->
                    onChange { it.copy(opacity = value) }
                }
                // BlobParams documents the solid-color radius fraction as 0f..0.9f.
                DebugSlider("Gradient falloff", blob.gradientFalloffFraction, 0f..0.9f) { value ->
                    onChange { it.copy(gradientFalloffFraction = value) }
                }
            }
        }
    }
}

@Composable
private fun DebugSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    suffix: String = "",
    onValueChange: (Float) -> Unit,
) {
    Column {
        DebugValueLabel(label, value, suffix)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DebugValueLabel(label: String, value: Float, suffix: String = "") {
    Text(
        text = "$label: ${"%.2f".format(value)}$suffix",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun DebugHueLabel(hue: Float) {
    Text(
        text = "Hue: ${hue.roundToInt()}\u00B0",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun DebugColorSwatch(color: Color) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(color))
}

// P5-T6 (R5-F11(b)): Home/player both render at ProductionAmbientBackgroundStyle's fixed
// 150dp blur/scrim, not the debug holder's blurRadiusDp — a slider that wrote to that field
// silently did nothing for what the user actually sees. Replaced with a read-only preview of
// the real production values instead of presenting a dead control.
@Composable
private fun ProductionAmbientPreview(baseSurfaceHue: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Production blur: ${ProductionAmbientBackgroundStyle.BlurRadiusDp.roundToInt()}dp (fixed)",
            style = MaterialTheme.typography.labelSmall,
        )
        val previewScrimColor = productionAmbientScrimColor(
            listOf(ExtractedSwatchHsl(baseSurfaceHue, saturation = 1f, lightness = 0.5f)),
        )
        if (previewScrimColor != null) {
            DebugColorSwatch(previewScrimColor)
        }
    }
}
