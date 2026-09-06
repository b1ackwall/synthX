package com.v1per.synthx.ui.screens.home

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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BlobParams
import com.v1per.synthx.ui.components.BlobShapeMode
import com.v1per.synthx.ui.components.RandomizerConfig
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// Temporary Home-only tuning controls; AmbientBlobBackground is the reusable renderer.
data class BlobBackgroundDebugState(
    val blobs: List<BlobParams> = List(6) { BlobParams() },
    val baseSurfaceHue: Float = 125f,
    val baseSurfaceSaturation: Float = 0.5f,
    val baseSurfaceLightness: Float = 0.08f,
    val blurRadiusPx: Float = 200f,
)

@Composable
fun HomeScreen() {
    var debugState by remember { mutableStateOf(BlobBackgroundDebugState()) }
    var blobShapeMode by remember { mutableStateOf(BlobShapeMode.Circle) }
    var showDebugSheet by remember { mutableStateOf(false) }
    val sheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()
    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        debugState.baseSurfaceSaturation,
        debugState.baseSurfaceLightness,
    )

    // MainScaffold's innerPadding already confines this screen to the safe area between
    // the system bars; the gradient clips its blur to those bounds itself.
    AmbientBlobBackground(
        blobs = debugState.blobs,
        baseSurfaceColor = baseSurfaceColor,
        blurRadiusPx = debugState.blurRadiusPx,
        shapeMode = blobShapeMode,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = rememberWordmark(),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
        TextButton(
            onClick = { showDebugSheet = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Text("Tune blobs")
        }

        if (showDebugSheet) {
            val dismiss: () -> Unit = {
                scope.launch {
                    sheetState.hide()
                    showDebugSheet = false
                }
            }
            // RefinedBottomSheet calls show() after measuring its available height.
            RefinedBottomSheet(
                onDismissRequest = dismiss,
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
                        TextButton(onClick = dismiss) { Text("Close") }
                    }
                    DebugSwitch(
                        label = "Shape: ${blobShapeMode.name}",
                        checked = blobShapeMode == BlobShapeMode.Organic,
                    ) { organic ->
                        blobShapeMode = if (organic) BlobShapeMode.Organic else BlobShapeMode.Circle
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Base surface", style = MaterialTheme.typography.titleSmall)
                        DebugColorSwatch(baseSurfaceColor)
                    }
                    DebugSlider("Hue", debugState.baseSurfaceHue, 0f..360f) {
                        debugState = debugState.copy(baseSurfaceHue = it)
                    }
                    DebugSlider("Saturation", debugState.baseSurfaceSaturation) {
                        debugState = debugState.copy(baseSurfaceSaturation = it)
                    }
                    DebugSlider("Lightness", debugState.baseSurfaceLightness) {
                        debugState = debugState.copy(baseSurfaceLightness = it)
                    }
                    DebugSlider("Blur (px)", debugState.blurRadiusPx, 0f..400f) {
                        debugState = debugState.copy(blurRadiusPx = it)
                    }
                    debugState.blobs.forEachIndexed { index, blob ->
                        key(index) {
                            BlobDebugSection(index, blob) { update ->
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
                DebugSlider("Hue", blob.hue, 0f..360f) { value ->
                    onChange { it.copy(hue = value) }
                }
                DebugSlider("Saturation", blob.saturation) { value ->
                    onChange { it.copy(saturation = value) }
                }
                DebugSlider("Lightness", blob.lightness) { value ->
                    onChange { it.copy(lightness = value) }
                }
                DebugSlider("Size", blob.sizeFraction) { value ->
                    onChange { it.copy(sizeFraction = value) }
                }
                DebugSlider("Speed (fraction/sec)", blob.speedFraction, 0.01f..0.5f) { value ->
                    onChange { it.copy(speedFraction = value) }
                }
                DebugSwitch(
                    label = if (blob.collisionUsesEdge) "Collision: edge-based" else "Collision: center-based",
                    checked = blob.collisionUsesEdge,
                ) { value ->
                    onChange { it.copy(collisionUsesEdge = value) }
                }
                DebugSlider("Opacity", blob.opacity) { value ->
                    onChange { it.copy(opacity = value) }
                }
                Text("Organic shape", style = MaterialTheme.typography.titleSmall)
                DebugSlider("Point count", blob.pointCount.toFloat(), 6f..10f, steps = 3) { value ->
                    onChange { it.copy(pointCount = value.roundToInt()) }
                }
                DebugSlider("Wobble amount", blob.wobbleAmountFraction, 0f..0.5f) { value ->
                    onChange { it.copy(wobbleAmountFraction = value) }
                }
                DebugSlider("Wobble frequency (ms)", blob.wobbleFrequencyMillis.toFloat(), 500f..8000f) { value ->
                    onChange { it.copy(wobbleFrequencyMillis = value.roundToInt()) }
                }
                DebugSlider("Wobble transition (ms)", blob.wobbleTransitionMillis.toFloat(), 200f..4000f) { value ->
                    onChange { it.copy(wobbleTransitionMillis = value.roundToInt()) }
                }
                BlobRandomizerSection(blob, onChange)
            }
        }
    }
}

@Composable
private fun BlobRandomizerSection(blob: BlobParams, onChange: ((BlobParams) -> BlobParams) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 12.dp),
        ) {
            Text("Randomize ${if (expanded) "−" else "+"}", style = MaterialTheme.typography.titleSmall)
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
                RandomizerControls("Opacity", blob.opacityRandomizer) { update ->
                    onChange { it.copy(opacityRandomizer = update(it.opacityRandomizer)) }
                }
                RandomizerControls("Size", blob.sizeRandomizer) { update ->
                    onChange { it.copy(sizeRandomizer = update(it.sizeRandomizer)) }
                }
            }
        }
    }
}

@Composable
private fun RandomizerControls(
    label: String,
    config: RandomizerConfig,
    onChange: ((RandomizerConfig) -> RandomizerConfig) -> Unit,
) {
    Column {
        DebugSwitch(label, config.enabled) { value ->
            onChange { it.copy(enabled = value) }
        }
        DebugSlider("Min", config.min) { value ->
            onChange { it.copy(min = value, max = maxOf(value, it.max)) }
        }
        DebugSlider("Max", config.max) { value ->
            onChange { it.copy(max = value, min = minOf(value, it.min)) }
        }
        DebugSlider("Frequency (ms)", config.frequencyMillis.toFloat(), 500f..15000f) { value ->
            onChange { it.copy(frequencyMillis = value.roundToInt()) }
        }
        DebugSlider("Transition (ms)", config.transitionMillis.toFloat(), 100f..5000f) { value ->
            onChange { it.copy(transitionMillis = value.roundToInt()) }
        }
    }
}

@Composable
private fun DebugSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DebugSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "$label: ${if (valueRange.endInclusive > 1f) value.roundToInt().toString() else "%.2f".format(value)}",
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DebugColorSwatch(color: Color) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(color))
}
