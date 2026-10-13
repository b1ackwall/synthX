package com.v1per.synthx.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tracing.Trace
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.ui.components.AmbientBlobBackground
import com.v1per.synthx.ui.components.BaseSurfaceLightness
import com.v1per.synthx.ui.components.BaseSurfaceSaturation
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import com.v1per.synthx.ui.components.HomeBlobDebugStateHolder
import com.v1per.synthx.ui.components.ProductionAmbientBackgroundStyle
import com.v1per.synthx.ui.components.productionAmbientScrim
import com.v1per.synthx.ui.components.productionAmbientScrimColor
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.SurfaceVariant

@Composable
internal fun HomeScreen(
    ambientPaused: Boolean = false,
) {
    Trace.beginSection(PerfTraceSections.HomeScreen)
    // The Developer Settings blob sheet's state, which starts at the renderer's default blobs.
    val debugState by HomeBlobDebugStateHolder.state

    val baseSurfaceColor = Color.hsl(
        debugState.baseSurfaceHue,
        BaseSurfaceSaturation,
        BaseSurfaceLightness,
    )

    // MainScaffold's innerPadding already confines this screen to the safe area between
    // the system bars; the gradient clips its blur to those bounds itself.
    Box(Modifier.fillMaxSize().testTag(PerfTestTags.HomeScreen).background(SurfaceVariant)) {
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
    Trace.endSection()
}
