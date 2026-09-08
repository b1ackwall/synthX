package com.v1per.synthx.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.components.animatedPlayerAlbumArtScale

@Composable
internal fun PlayerAlbumArtWithGlow(
    song: Song,
    isPlaying: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    // Keep the layout footprint equal to the art throughout the morph. The larger
    // glow overflows symmetrically without moving the art or changing its size.
    Box(modifier = modifier.size(size)) {
        val artScale = animatedPlayerAlbumArtScale(isPlaying)
        val glowColor = Color.Black.copy(alpha = 0.9f)
        val glowSize = size + 8f.dp * 2
        Box(
            modifier = Modifier
                // requiredSize preserves the spread even under the mini art's
                // tight constraints; Compose centers the overflow automatically.
                .requiredSize(glowSize)
                .align(Alignment.Center)
                .scale(artScale)
                .blur(
                    radius = 80f.dp,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                )
                .background(color = glowColor, shape = MaterialTheme.shapes.small),
        )
        PlayerAlbumArt(
            song = song,
            isPlaying = isPlaying,
            size = size,
            fullResolution = true,
            scale = artScale,
        )
    }
}
