package com.v1per.synthx.ui.player

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

private val NeutralPlayerBlobs = DefaultInitialBlobs.map { it.copy(opacity = 0f) }

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
    val scrimColor = state?.let {
        Color.hsl(it.artworkSwatches[0].hue, 0.3f, 0.05f).copy(alpha = 0.5f)
    }
    val backgroundModifier = remember(backgroundBoundsModifier, scrimColor) {
        if (scrimColor == null) {
            backgroundBoundsModifier
        } else {
            backgroundBoundsModifier.drawWithContent {
                // Composite the opaque base and already-blurred blobs first. Player art and
                // controls are later siblings, outside this background-only draw pass.
                drawContent()
                drawRect(color = scrimColor, blendMode = BlendMode.SrcOver)
            }
        }
    }

    AmbientBlobBackground(
        blobs = state?.blobs ?: NeutralPlayerBlobs,
        baseSurfaceColor = state?.baseSurfaceColor ?: SurfaceVariant,
        blurRadiusDp = 150f.dp,
        // Resume within the first 0.1% of travel, retaining the collapsed static frame.
        paused = state == null || progress < 0.001f,
        // Base opacity is zero too; this also defeats any future opacity randomizer without
        // changing its configuration (and therefore without disposing its remembered phase).
        blobAlphaMultiplier = if (state == null) 0f else 1f,
        // Dragging changes the outer clip without otherwise affecting motion.
        modifier = backgroundModifier,
    )
}
