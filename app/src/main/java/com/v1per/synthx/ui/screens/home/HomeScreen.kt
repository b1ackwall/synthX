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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BlobParams
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.extractArtworkSwatches
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Temporary Home-only tuning controls; AmbientBlobBackground is the reusable renderer.
internal data class BlobBackgroundDebugState(
    // Size/opacity randomizers stay disabled; these are the editable testbed defaults.
    val blobs: List<BlobParams> = listOf(
        BlobParams(sizeFraction = 1.0f, opacity = 0.4f, gradientFalloffFraction = 0.25f),
        BlobParams(sizeFraction = 0.8f, opacity = 0.6f, gradientFalloffFraction = 0.5f),
        BlobParams(sizeFraction = 0.6f, opacity = 0.8f, gradientFalloffFraction = 0.75f),
    ),
    val baseSurfaceHue: Float = 125f,
    val blurRadiusDp: Float = 150f,
) {
    val baseSurfaceSaturation: Float = 0.5f
    val baseSurfaceLightness: Float = 0.1f
}

// Process-lifetime testbed state; no preferences to hydrate or Application setup needed.
private object HomeBlobDebugStateHolder {
    val state = mutableStateOf(BlobBackgroundDebugState())
    var lastPaletteSongId: String? = null
}

@Composable
fun HomeScreen(playbackViewModel: PlaybackViewModel) {
    var debugState by HomeBlobDebugStateHolder.state
    val currentSong by playbackViewModel.currentSong.collectAsState()
    val context = LocalContext.current.applicationContext

    LaunchedEffect(currentSong?.id) {
        val song = currentSong ?: return@LaunchedEffect
        if (HomeBlobDebugStateHolder.lastPaletteSongId == song.id) return@LaunchedEffect
        val uri = song.albumArtFullUri ?: song.albumArtUri ?: return@LaunchedEffect
        val swatches = extractArtworkSwatches(context, uri)
        if (swatches.isEmpty()) return@LaunchedEffect
        // Also guard against a playback change before its recomposition cancels this effect.
        if (playbackViewModel.currentSong.value?.id != song.id) return@LaunchedEffect
        // Read the current singleton value after loading, preserving edits made meanwhile.
        debugState = debugState.copy(
            // Base and Blob 1 deliberately share the most populous selected hue.
            baseSurfaceHue = swatches[0].hue,
            blobs = debugState.blobs.mapIndexed { index, blob ->
                swatches.getOrNull(index)?.let {
                    // Additive (BlendMode.Plus) blending blows out light colours, so
                    // intensity scales inversely with this blob's own extracted lightness.
                    val derivedOpacity = (1f - it.lightness).coerceIn(0f, 1f)
                    val derivedFalloff = (1f - it.lightness).coerceIn(0f, 0.9f)
                    blob.copy(
                        hue = it.hue,
                        saturation = it.saturation,
                        lightness = it.lightness,
                        opacity = derivedOpacity,
                        gradientFalloffFraction = derivedFalloff,
                    )
                } ?: blob
            },
        )
        HomeBlobDebugStateHolder.lastPaletteSongId = song.id
    }

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
        blurRadiusDp = debugState.blurRadiusDp.dp,
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Base surface", style = MaterialTheme.typography.titleSmall)
                        DebugColorSwatch(baseSurfaceColor)
                    }
                    DebugHueLabel(debugState.baseSurfaceHue)
                    DebugSlider(
                        label = "Blur",
                        value = debugState.blurRadiusDp,
                        valueRange = 0f..600f,
                        suffix = " dp",
                    ) { value ->
                        debugState = debugState.copy(blurRadiusDp = value)
                    }
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
