package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.deriveBlobColors
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.SurfaceVariant

@Composable
fun HomeScreen(
    playbackViewModel: PlaybackViewModel,
    ambientPaused: Boolean = false,
) {
    var debugState by HomeBlobDebugStateHolder.state
    val currentSong by playbackViewModel.currentSong.collectAsState()
    val paletteSwatches = currentSong?.paletteSwatches

    LaunchedEffect(currentSong?.id, paletteSwatches) {
        val song = currentSong ?: return@LaunchedEffect
        val swatches = paletteSwatches ?: return@LaunchedEffect
        if (HomeBlobDebugStateHolder.lastPaletteSongId == song.id &&
            HomeBlobDebugStateHolder.lastPaletteSwatches == swatches
        ) return@LaunchedEffect
        val colors = deriveBlobColors(swatches.map { ExtractedSwatchHsl(it.hue, it.saturation, it.lightness) })
            ?: return@LaunchedEffect
        // Apply only when the song/palette changes; ordinary tuning edits remain intact.
        debugState = debugState.copy(
            baseSurfaceHue = colors.baseSurfaceHue,
            blobs = colors.applyTo(debugState.blobs),
        )
        HomeBlobDebugStateHolder.lastPaletteSongId = song.id
        HomeBlobDebugStateHolder.lastPaletteSwatches = swatches
    }

    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        BaseSurfaceSaturation,
        BaseSurfaceLightness,
    )
    val hasPalette = paletteSwatches != null
    val renderedBlobs = remember(debugState.blobs, hasPalette) {
        if (hasPalette) debugState.blobs else debugState.blobs.map { it.copy(opacity = 0f) }
    }

    // MainScaffold's innerPadding already confines this screen to the safe area between
    // the system bars; the gradient clips its blur to those bounds itself.
    Box(Modifier.fillMaxSize().background(SurfaceVariant)) {
        AmbientBlobBackground(
            blobs = renderedBlobs,
            baseSurfaceColor = if (hasPalette) baseSurfaceColor else SurfaceVariant,
            blurRadiusDp = debugState.blurRadiusDp.dp,
            paused = ambientPaused || !hasPalette,
            // A debug opacity randomizer can override BlobParams.opacity, so suppress only
            // drawing while retaining the randomizer's enabled structure and remembered phase.
            blobAlphaMultiplier = if (hasPalette) 1f else 0f,
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = rememberWordmark(),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
