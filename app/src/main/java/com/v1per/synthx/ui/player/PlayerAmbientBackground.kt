package com.v1per.synthx.ui.player

// DEAD CODE: the player draws PlayerAltStageBackground; this blob wrapper has no callers. Delete in a cleanup pass. Ask  before deleting.
// import androidx.compose.foundation.layout.BoxScope
// import androidx.compose.foundation.layout.fillMaxWidth
// import androidx.compose.foundation.layout.requiredHeight
// import androidx.compose.foundation.layout.wrapContentHeight
// import androidx.compose.runtime.Composable
// import androidx.compose.runtime.remember
// import androidx.compose.ui.Alignment
// import androidx.compose.ui.Modifier
// import androidx.compose.ui.graphics.Color
// import androidx.compose.ui.unit.Dp
// import androidx.compose.ui.unit.dp
// import com.v1per.synthx.ui.components.AmbientBlobBackground
// import com.v1per.synthx.ui.components.BaseSurfaceLightness
// import com.v1per.synthx.ui.components.BaseSurfaceSaturation
// import com.v1per.synthx.ui.components.BlobParams
// import com.v1per.synthx.ui.components.DefaultInitialBlobs
// import com.v1per.synthx.ui.components.DerivedBlobColors
// import com.v1per.synthx.ui.components.ExtractedSwatchHsl
// import com.v1per.synthx.ui.components.ProductionAmbientBackgroundStyle
// import com.v1per.synthx.ui.components.productionAmbientScrim
// import com.v1per.synthx.ui.components.productionAmbientScrimColor
// import com.v1per.synthx.ui.theme.SurfaceVariant
//
// internal data class PlayerAmbientBackgroundState(
//     val blobs: List<BlobParams>,
//     val baseSurfaceColor: Color,
//     val artworkSwatches: List<ExtractedSwatchHsl>,
// )
//
// private val NeutralPlayerBlobs = DefaultInitialBlobs.map { it.copy(opacity = 0f) }
//
// @Composable
// internal fun rememberPlayerAmbientBackgroundState(colors: DerivedBlobColors?): PlayerAmbientBackgroundState? =
//     remember(colors) { playerAmbientBackgroundState(colors) }
//
// internal fun playerAmbientBackgroundState(colors: DerivedBlobColors?): PlayerAmbientBackgroundState? =
//     colors?.let {
//         PlayerAmbientBackgroundState(
//             blobs = colors.applyTo(DefaultInitialBlobs),
//             baseSurfaceColor = Color.hsl(colors.baseSurfaceHue, BaseSurfaceSaturation, BaseSurfaceLightness),
//             artworkSwatches = colors.artworkSwatches,
//         )
//     }
//
// @Composable
// internal fun BoxScope.PlayerAmbientBackground(
//     state: PlayerAmbientBackgroundState?,
//     fullHeight: Dp,
//     progress: Float,
// ) {
//     // Keep the same full-height, bottom-anchored footprint for both rendering branches.
//     val backgroundBoundsModifier = remember(fullHeight) {
//         Modifier
//             .fillMaxWidth()
//             .align(Alignment.BottomCenter)
//             .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
//             .requiredHeight(fullHeight)
//     }
//     val scrimColor = productionAmbientScrimColor(state?.artworkSwatches)
//     val backgroundModifier = remember(backgroundBoundsModifier, scrimColor) {
//         // Player art and controls are later siblings, outside this background-only draw pass.
//         backgroundBoundsModifier.productionAmbientScrim(scrimColor)
//     }
//
//     AmbientBlobBackground(
//         blobs = state?.blobs ?: NeutralPlayerBlobs,
//         baseSurfaceColor = state?.baseSurfaceColor ?: SurfaceVariant,
//         blurRadiusDp = ProductionAmbientBackgroundStyle.BlurRadiusDp.dp,
//         // Resume within the first 0.1% of travel, retaining the collapsed static frame.
//         paused = state == null || progress < 0.001f,
//         // Base opacity is zero too; this also defeats any future opacity randomizer without
//         // changing its configuration (and therefore without disposing its remembered phase).
//         blobAlphaMultiplier = if (state == null) 0f else 1f,
//         // Dragging changes the outer clip without otherwise affecting motion.
//         modifier = backgroundModifier,
//     )
// }
