package com.v1per.synthx.ui.screens.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BlobParams
import com.v1per.synthx.ui.components.RefinedBottomSheet
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// Temporary Home-only tuning controls; AmbientBlobBackground is the reusable renderer.
data class BlobBackgroundDebugState(
    // Size/opacity randomizers stay disabled so these Home testbed values remain fixed.
    val blobs: List<BlobParams> = listOf(
        BlobParams(sizeFraction = 1.0f, opacity = 0.4f, gradientFalloffFraction = 0.25f),
        BlobParams(sizeFraction = 0.8f, opacity = 0.6f, gradientFalloffFraction = 0.5f),
        BlobParams(sizeFraction = 0.6f, opacity = 0.8f, gradientFalloffFraction = 0.75f),
    ),
    val baseSurfaceHue: Float = 125f,
    // Reference values by blob index, separate from the editable rendering parameters.
    val extractedSwatches: List<ExtractedSwatchHsl> = emptyList(),
) {
    val blurRadiusDp: Float = 150f
    val baseSurfaceSaturation: Float = 0.5f
    val baseSurfaceLightness: Float = 0.1f
}

data class ExtractedSwatchHsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

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
                swatches.getOrNull(index)?.let { blob.copy(hue = it.hue) } ?: blob
            },
            extractedSwatches = swatches,
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
                    DebugValueLabel("Blur", debugState.blurRadiusDp, suffix = " dp")
                    debugState.blobs.forEachIndexed { index, blob ->
                        key(index) {
                            BlobDebugSection(
                                index = index,
                                blob = blob,
                                extractedSwatch = debugState.extractedSwatches.getOrNull(index),
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
    extractedSwatch: ExtractedSwatchHsl?,
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
                Text(
                    text = extractedSwatch?.let {
                        "Extracted: S ${"%.2f".format(it.saturation)}, L ${"%.2f".format(it.lightness)}"
                    } ?: "Extracted: unavailable",
                    style = MaterialTheme.typography.labelSmall,
                )
                DebugSlider("Saturation", blob.saturation) { value ->
                    onChange { it.copy(saturation = value) }
                }
                DebugSlider("Lightness", blob.lightness) { value ->
                    onChange { it.copy(lightness = value) }
                }
                DebugValueLabel("Size", blob.sizeFraction)
                DebugValueLabel("Opacity", blob.opacity)
                DebugValueLabel("Gradient falloff", blob.gradientFalloffFraction)
            }
        }
    }
}

@Composable
private fun DebugSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
) {
    Column {
        DebugValueLabel(label, value)
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

private suspend fun extractArtworkSwatches(context: Context, uri: String): List<ExtractedSwatchHsl> =
    withContext(Dispatchers.Default) {
        try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                // Palette reads pixels on the CPU; hardware bitmaps do not support that.
                .allowHardware(false)
                .size(256)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult
                ?: return@withContext emptyList()
            val bitmap = result.drawable.toPaletteBitmap()
            val swatches = Palette.from(bitmap).generate().swatches
            val hsl = FloatArray(3)
            val candidates = swatches.sortedByDescending { it.population }.map { swatch ->
                ColorUtils.colorToHSL(swatch.rgb, hsl)
                ExtractedSwatchHsl(hsl[0], hsl[1], hsl[2])
            }
            val selected = mutableListOf<ExtractedSwatchHsl>()
            for (threshold in 20 downTo 1) {
                // Restart the whole greedy pass; retain population order at each threshold.
                selected.clear()
                for (candidate in candidates) {
                    if (selected.all { previous ->
                            val distance = abs(candidate.hue - previous.hue)
                            min(distance, 360f - distance) > threshold.toFloat()
                        }
                    ) {
                        selected += candidate
                        if (selected.size == 3) break
                    }
                }
                if (selected.size == 3) break
            }
            // Reuse only after exhausting the 1-degree pass, preserving full swatch values.
            if (selected.isEmpty()) emptyList() else List(3) { selected[it % selected.size] }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Missing/unreadable artwork or failed quantization keeps hues and references.
            emptyList()
        }
    }

private fun Drawable.toPaletteBitmap(): Bitmap {
    if (this is BitmapDrawable) return bitmap
    // Bound the fallback allocation, including drawables without intrinsic dimensions.
    val width = intrinsicWidth.coerceIn(1, 256)
    val height = intrinsicHeight.coerceIn(1, 256)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val originalBounds = Rect(bounds)
    try {
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
    } finally {
        bounds = originalBounds
    }
    return bitmap
}
