package com.v1per.synthx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Motion

@Composable
fun PlayerAlbumArt(
    song: Song,
    isPlaying: Boolean,
    size: Dp,
    fullResolution: Boolean = false,
) {
    // "Breathe" — the art relaxes inward when paused and springs back on play.
    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.90f,
        animationSpec = Motion.SpringStandard,
        label = "artScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .scale(scale)
            .clip(MaterialTheme.shapes.small),
    ) {
        if (song.albumArtUri != null) {
            // The mini thumbnail loads at its 44dp display size. The expanded player
            // opts into original resolution so Coil decodes the artwork at full size
            // instead of upscaling the small cached thumbnail.
            AsyncImage(
                model = crossfadeModel(song.albumArtUri, original = fullResolution),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GlitchPlaceholder(
                songId = song.id,
                isPlaying = isPlaying,
                size = size,
            )
        }
    }
}
