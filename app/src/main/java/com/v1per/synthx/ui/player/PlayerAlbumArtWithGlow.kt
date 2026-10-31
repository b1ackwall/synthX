package com.v1per.synthx.ui.player

import android.annotation.SuppressLint
import android.graphics.BlurMaskFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
        // Quantized so the glow box size changes in coarse steps during a sheet drag.
        val glowSize = quantizePlayerGlowSize(size + GlowSpread * 2)
        Box(
            modifier = Modifier
                // requiredSize preserves the spread even under the mini art's
                // tight constraints; Compose centers the overflow automatically.
                .requiredSize(glowSize)
                .align(Alignment.Center)
                .scale(artScale)
                .maskBlurGlow(glowColor),
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

/** Draws the glow as the shape filled with a blur mask filter of [GlowBlurRadius]; no layer is created. */
@Composable
private fun Modifier.maskBlurGlow(color: Color): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val shape = MaterialTheme.shapes.small
    val paint = remember(density.density, color) {
        Paint().also {
            it.color = color
            it.nativePaint.maskFilter =
                BlurMaskFilter(with(density) { GlowBlurRadius.toPx() }, BlurMaskFilter.Blur.NORMAL)
        }
    }
    return drawBehind {
        drawIntoCanvas { canvas -> canvas.drawOutline(shape.createOutline(size, layoutDirection, this), paint) }
    }
}
