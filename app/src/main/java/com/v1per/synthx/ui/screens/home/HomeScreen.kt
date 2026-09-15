package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
import com.v1per.synthx.ui.components.BlobBackgroundDebugState
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.DerivedBlobColors
import com.v1per.synthx.ui.components.ProductionAmbientBackgroundStyle
import com.v1per.synthx.ui.components.productionAmbientScrim
import com.v1per.synthx.ui.components.productionAmbientScrimColor
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.SurfaceVariant

@Composable
internal fun HomeScreen(
    ambientColors: DerivedBlobColors?,
    ambientPaused: Boolean = false,
) {
    var debugState by HomeBlobDebugStateHolder.state

    LaunchedEffect(ambientColors) {
        val colors = ambientColors ?: return@LaunchedEffect
        // MainScaffold already chose the slots. Apply only received values here so ordinary
        // developer tuning edits remain intact until the shared song/palette model changes.
        debugState = applyHomeAmbientColors(debugState, colors)
    }

    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        BaseSurfaceSaturation,
        BaseSurfaceLightness,
    )
    val hasPalette = ambientColors != null
    val renderedBlobs = remember(debugState.blobs, hasPalette) {
        if (hasPalette) debugState.blobs else debugState.blobs.map { it.copy(opacity = 0f) }
    }

    // MainScaffold's innerPadding already confines this screen to the safe area between
    // the system bars; the gradient clips its blur to those bounds itself.
    Box(Modifier.fillMaxSize().background(SurfaceVariant)) {
        AmbientBlobBackground(
            blobs = renderedBlobs,
            baseSurfaceColor = if (hasPalette) baseSurfaceColor else SurfaceVariant,
            // The debug state's 40dp slider remains a testbed value, not production policy.
            blurRadiusDp = ProductionAmbientBackgroundStyle.BlurRadiusDp.dp,
            paused = ambientPaused || !hasPalette,
            // A debug opacity randomizer can override BlobParams.opacity, so suppress only
            // drawing while retaining the randomizer's enabled structure and remembered phase.
            blobAlphaMultiplier = if (hasPalette) 1f else 0f,
            modifier = Modifier.productionAmbientScrim(productionAmbientScrimColor(ambientColors)),
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
    }
}

internal fun applyHomeAmbientColors(
    debugState: BlobBackgroundDebugState,
    colors: DerivedBlobColors,
): BlobBackgroundDebugState = debugState.copy(
    baseSurfaceHue = colors.baseSurfaceHue,
    blobs = colors.applyTo(debugState.blobs),
)
