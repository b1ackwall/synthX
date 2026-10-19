package com.v1per.synthx.ui.player

import android.annotation.SuppressLint
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
import androidx.tracing.Trace
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.ui.components.PlayerAlbumArt
import com.v1per.synthx.ui.components.animatedPlayerAlbumArtScale
import kotlin.math.round

/** The mini player's art size. */
private val MiniPlayerArtSize = 44.dp

/** How far the glow extends past the art on each side. */
private val GlowSpread = 2.dp
private val GlowBlurRadius = 20.dp
private const val GlowAlpha = 0.8f

/** Quantization anchor: the mini art plus the spread on both sides. */
private val MiniPlayerGlowSize = MiniPlayerArtSize + GlowSpread * 2

/** Glow size quantization step, in dp. */
private const val GlowSizeStepDp = 4f

internal fun quantizePlayerGlowSize(size: Dp): Dp {
    val stepsFromMini = round((size.value - MiniPlayerGlowSize.value) / GlowSizeStepDp)
    return (MiniPlayerGlowSize.value + stepsFromMini * GlowSizeStepDp).dp
}

// Body is bracketed by begin/endSection (PerfTraceSections); try/finally cannot wrap composables.
@SuppressLint("UnclosedTrace")
@Composable
internal fun PlayerAlbumArtWithGlow(song: Song, isPlaying: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Trace.beginSection(PerfTraceSections.AlbumArtWithGlow)
    // Keep the layout footprint equal to the art throughout the morph. The larger
    // glow overflows symmetrically without moving the art or changing its size.
    Box(modifier = modifier.size(size)) {
        val artScale = animatedPlayerAlbumArtScale(isPlaying)
        val glowColor = Color.Black.copy(alpha = GlowAlpha)
        // Quantized so the RenderEffect layer is not reallocated/rasterized at every
        // one-pixel size change during a sheet drag.
        val glowSize = quantizePlayerGlowSize(size + GlowSpread * 2)
        Box(
            modifier = Modifier
                // requiredSize preserves the spread even under the mini art's
                // tight constraints; Compose centers the overflow automatically.
                .requiredSize(glowSize)
                .align(Alignment.Center)
                .scale(artScale)
                .blur(
                    radius = GlowBlurRadius,
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
    Trace.endSection()
}
