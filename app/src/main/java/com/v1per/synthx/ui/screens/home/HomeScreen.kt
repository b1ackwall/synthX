package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.deriveBlobColors
import com.v1per.synthx.ui.components.extractArtworkSwatches
import com.v1per.synthx.ui.components.hiddenFromAccessibilityWhileSheetShown
import com.v1per.synthx.ui.components.rememberRefinedBottomSheetState
import com.v1per.synthx.ui.components.rememberWordmark
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(playbackViewModel: PlaybackViewModel) {
    var debugState by HomeBlobDebugStateHolder.state
    val currentSong by playbackViewModel.currentSong.collectAsState()
    val context = LocalContext.current.applicationContext
    var showArtPositionTuneSheet by remember { mutableStateOf(false) }
    val artPositionTuneSheetState = rememberRefinedBottomSheetState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentSong?.id) {
        val song = currentSong ?: return@LaunchedEffect
        if (HomeBlobDebugStateHolder.lastPaletteSongId == song.id) return@LaunchedEffect
        val uri = song.albumArtFullUri ?: song.albumArtUri ?: return@LaunchedEffect
        val swatches = extractArtworkSwatches(context, uri)
        if (swatches.isEmpty()) return@LaunchedEffect
        // Also guard against a playback change before its recomposition cancels this effect.
        if (playbackViewModel.currentSong.value?.id != song.id) return@LaunchedEffect
        val colors = deriveBlobColors(swatches) ?: return@LaunchedEffect
        // Read the current singleton value after loading, preserving edits made meanwhile.
        debugState = debugState.copy(
            baseSurfaceHue = colors.baseSurfaceHue,
            blobs = colors.applyTo(debugState.blobs),
        )
        HomeBlobDebugStateHolder.lastPaletteSongId = song.id
    }

    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        BaseSurfaceSaturation,
        BaseSurfaceLightness,
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
            Column(
                modifier = Modifier.hiddenFromAccessibilityWhileSheetShown(showArtPositionTuneSheet),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = rememberWordmark(),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                )
                TextButton(onClick = { showArtPositionTuneSheet = true }) {
                    Text("Tune art position")
                }
            }

            if (showArtPositionTuneSheet) {
                ArtPositionTuneSheet(
                    sheetState = artPositionTuneSheetState,
                    onDismissRequest = {
                        scope.launch {
                            artPositionTuneSheetState.hide()
                            showArtPositionTuneSheet = false
                        }
                    },
                )
            }
        }
    }
}
