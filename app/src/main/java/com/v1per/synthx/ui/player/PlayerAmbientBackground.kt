package com.v1per.synthx.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
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
import com.v1per.synthx.ui.theme.SurfaceVariant

internal data class PlayerAmbientBackgroundState(
    val blobs: List<BlobParams>,
    val baseSurfaceColor: Color,
    val artworkSwatches: List<ExtractedSwatchHsl>,
)

@Composable
internal fun rememberPlayerAmbientBackgroundState(song: Song): PlayerAmbientBackgroundState? =
    remember(song.id, song.paletteSwatches) {
        val swatches = song.paletteSwatches ?: return@remember null
        val colors = deriveBlobColors(swatches.map { ExtractedSwatchHsl(it.hue, it.saturation, it.lightness) })
            ?: return@remember null
        PlayerAmbientBackgroundState(
            blobs = colors.applyTo(DefaultInitialBlobs),
            baseSurfaceColor = Color.hsl(colors.baseSurfaceHue, BaseSurfaceSaturation, BaseSurfaceLightness),
            artworkSwatches = colors.artworkSwatches,
        )
    }

@Composable
internal fun BoxScope.PlayerAmbientBackground(
    state: PlayerAmbientBackgroundState?,
    fullHeight: Dp,
    progress: Float,
) {
    // Keep the same full-height, bottom-anchored footprint for both rendering branches.
    val backgroundBoundsModifier = remember(fullHeight) {
        Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
            .requiredHeight(fullHeight)
    }
    if (state == null) {
        Box(backgroundBoundsModifier.background(SurfaceVariant))
        return
    }
    val scrimColor = Color.hsl(state.artworkSwatches[0].hue, 0.3f, 0.05f).copy(alpha = 0.5f)
    val backgroundModifier = remember(backgroundBoundsModifier, scrimColor) {
        backgroundBoundsModifier.drawWithContent {
            // Composite the opaque base and already-blurred blobs first. Player art and
            // controls are later siblings, outside this background-only draw pass.
            drawContent()
            drawRect(color = scrimColor, blendMode = BlendMode.SrcOver)
        }
    }

    AmbientBlobBackground(
        blobs = state.blobs,
        baseSurfaceColor = state.baseSurfaceColor,
        blurRadiusDp = 150f.dp,
        // Resume within the first 0.1% of travel, retaining the collapsed static frame.
        paused = progress < 0.001f,
        // Dragging changes the outer clip; only full collapse pauses motion.
        modifier = backgroundModifier,
    )
}
