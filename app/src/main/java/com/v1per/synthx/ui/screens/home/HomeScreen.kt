package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.extractArtworkSwatches
import com.v1per.synthx.ui.components.rememberWordmark

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
    }
}
