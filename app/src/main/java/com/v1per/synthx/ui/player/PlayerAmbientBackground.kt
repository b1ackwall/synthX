package com.v1per.synthx.ui.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
import com.v1per.synthx.ui.components.BlobParams
import com.v1per.synthx.ui.components.DefaultInitialBlobs
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.deriveBlobColors
import com.v1per.synthx.ui.components.extractArtworkSwatches

internal data class PlayerAmbientBackgroundState(
    val blobs: List<BlobParams>,
    val baseSurfaceColor: Color,
    val artworkSwatches: List<ExtractedSwatchHsl>,
)

@Composable
internal fun rememberPlayerAmbientBackgroundState(song: Song): PlayerAmbientBackgroundState {
    val context = LocalContext.current.applicationContext
    var blobs by remember { mutableStateOf(DefaultInitialBlobs) }
    var baseSurfaceColor by remember {
        mutableStateOf(Color.hsl(125f, BaseSurfaceSaturation, BaseSurfaceLightness))
    }
    // Always three entries, including before artwork loads or when extraction fails.
    // Keep the same fallback tones as the player's existing blobs.
    var artworkSwatches by remember {
        mutableStateOf(blobs.map { ExtractedSwatchHsl(it.hue, it.saturation, it.lightness) })
    }
    val currentSongId by rememberUpdatedState(song.id)

    LaunchedEffect(song.id) {
        val uri = song.albumArtFullUri ?: song.albumArtUri ?: return@LaunchedEffect
        val swatches = extractArtworkSwatches(context, uri)
        if (swatches.size != 3) return@LaunchedEffect
        if (currentSongId != song.id) return@LaunchedEffect
        val colors = deriveBlobColors(swatches) ?: return@LaunchedEffect
        artworkSwatches = swatches
        blobs = colors.applyTo(blobs)
        // The base shares Blob 1's hue, with its own fixed saturation and lightness.
        baseSurfaceColor = Color.hsl(colors.baseSurfaceHue, BaseSurfaceSaturation, BaseSurfaceLightness)
    }

    return PlayerAmbientBackgroundState(blobs, baseSurfaceColor, artworkSwatches)
}

@Composable
internal fun BoxScope.PlayerAmbientBackground(
    state: PlayerAmbientBackgroundState,
    fullHeight: Dp,
    progress: Float,
) {
    AmbientBlobBackground(
        blobs = state.blobs,
        baseSurfaceColor = state.baseSurfaceColor,
        blurRadiusDp = 150f.dp,
        // Resume within the first 0.1% of travel, retaining the collapsed static frame.
        paused = progress < 0.001f,
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            // Escape the animated sheet's height constraint and bottom-anchor
            // the overflow. The renderer always measures at full height, so
            // dragging changes the outer clip; only full collapse pauses motion.
            .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
            .requiredHeight(fullHeight),
    )
}
