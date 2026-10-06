package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// DEAD CODE: only Home's palette-following code used these. Delete in a cleanup pass. Ask  before deleting.
// import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
// DEAD CODE: only Home's palette-following code used these. Delete in a cleanup pass. Ask  before deleting.
// import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
// DEAD CODE: only Home's palette-following code used these. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.components.BlobBackgroundDebugState
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
// DEAD CODE: only Home's palette-following code used these. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.components.DerivedBlobColors
import com.v1per.synthx.ui.components.ProductionAmbientBackgroundStyle
import com.v1per.synthx.ui.components.productionAmbientScrim
import com.v1per.synthx.ui.components.productionAmbientScrimColor
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.SurfaceVariant

@Composable
internal fun HomeScreen(
    // DEAD CODE: Home no longer follows the playing song's palette. Delete in a cleanup pass. Ask  before deleting.
    // ambientColors: DerivedBlobColors?,
    ambientPaused: Boolean = false,
) {
    // The Developer Settings blob sheet's state, which starts at the renderer's default blobs.
    var debugState by HomeBlobDebugStateHolder.state

    // DEAD CODE: Home no longer applies the playing song's palette. Delete in a cleanup pass. Ask  before deleting.
    // LaunchedEffect(ambientColors) {
    //     val colors = ambientColors ?: return@LaunchedEffect
    //     // MainScaffold already chose the slots. Apply only received values here so ordinary
    //     // developer tuning edits remain intact until the shared song/palette model changes.
    //     debugState = applyHomeAmbientColors(debugState, colors)
    // }

    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        BaseSurfaceSaturation,
        BaseSurfaceLightness,
    )
    // DEAD CODE: Home's blobs no longer depend on a palette being present. Delete in a cleanup pass. Ask  before deleting.
    // val hasPalette = ambientColors != null
    // val renderedBlobs = remember(debugState.blobs, hasPalette) {
    //     if (hasPalette) debugState.blobs else debugState.blobs.map { it.copy(opacity = 0f) }
    // }

    // MainScaffold's innerPadding already confines this screen to the safe area between
    // the system bars; the gradient clips its blur to those bounds itself.
    Box(Modifier.fillMaxSize().background(SurfaceVariant)) {
        // DEAD CODE: palette-gated call, replaced by the always-visible call below. Delete in a cleanup pass. Ask  before deleting.
        // AmbientBlobBackground(
        //     blobs = renderedBlobs,
        //     baseSurfaceColor = if (hasPalette) baseSurfaceColor else SurfaceVariant,
        //     // The debug state's 40dp slider remains a testbed value, not production policy.
        //     blurRadiusDp = ProductionAmbientBackgroundStyle.BlurRadiusDp.dp,
        //     paused = ambientPaused || !hasPalette,
        //     // A debug opacity randomizer can override BlobParams.opacity, so suppress only
        //     // drawing while retaining the randomizer's enabled structure and remembered phase.
        //     blobAlphaMultiplier = if (hasPalette) 1f else 0f,
        //     modifier = Modifier.productionAmbientScrim(productionAmbientScrimColor(ambientColors)),
        // )
        AmbientBlobBackground(
            blobs = debugState.blobs,
            baseSurfaceColor = baseSurfaceColor,
            blurRadiusDp = ProductionAmbientBackgroundStyle.BlurRadiusDp.dp,
            paused = ambientPaused,
            // The scrim takes its hue from the base surface hue, as the Developer Settings preview does.
            modifier = Modifier.productionAmbientScrim(
                productionAmbientScrimColor(
                    listOf(ExtractedSwatchHsl(debugState.baseSurfaceHue, saturation = 1f, lightness = 0.5f)),
                ),
            ),
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
        if (BuildConfig.DEBUG) PlayerBackgroundDebugControls()
    }
}

// DEAD CODE: Home no longer applies the playing song's palette. Delete in a cleanup pass. Ask  before deleting.
// internal fun applyHomeAmbientColors(
//     debugState: BlobBackgroundDebugState,
//     colors: DerivedBlobColors,
// ): BlobBackgroundDebugState = debugState.copy(
//     baseSurfaceHue = colors.baseSurfaceHue,
//     blobs = colors.applyTo(debugState.blobs),
// )
