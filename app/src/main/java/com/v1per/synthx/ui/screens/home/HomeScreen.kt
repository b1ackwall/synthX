package com.v1per.synthx.ui.screens.home

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tracing.Trace
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.ui.components.rememberWordmark
import com.v1per.synthx.ui.theme.Background

// Body is bracketed by begin/endSection (PerfTraceSections); try/finally cannot wrap composables.
@SuppressLint("UnclosedTrace")
@Composable
internal fun HomeScreen(
    // Unused: Home draws a solid background, so there is no ambient motion to pause.
    @Suppress("UNUSED_PARAMETER") ambientPaused: Boolean = false,
) {
    Trace.beginSection(PerfTraceSections.HomeScreen)
    // DEAD CODE: Home uses a solid Background since the 150 dp blob blur saturated the Redmi
    // (docs/perf/home-solid-background.md). Delete in a cleanup pass. Ask  before deleting.
    // val debugState by HomeBlobDebugStateHolder.state
    // val baseSurfaceColor = Color.hsl(
    //     debugState.baseSurfaceHue,
    //     BaseSurfaceSaturation,
    //     BaseSurfaceLightness,
    // )

    // Solid app base surface, matching the other tabs' Background.
    Box(Modifier.fillMaxSize().testTag(PerfTestTags.HomeScreen).background(Background)) {
        // DEAD CODE: the blob background Home drew before the solid Background above.
        // Delete in a cleanup pass. Ask  before deleting.
        // AmbientBlobBackground(
        //     blobs = debugState.blobs,
        //     baseSurfaceColor = baseSurfaceColor,
        //     blurRadiusDp = ProductionAmbientBackgroundStyle.BlurRadiusDp.dp,
        //     paused = ambientPaused,
        //     modifier = Modifier.productionAmbientScrim(
        //         productionAmbientScrimColor(
        //             listOf(ExtractedSwatchHsl(debugState.baseSurfaceHue, saturation = 1f, lightness = 0.5f)),
        //         ),
        //     ),
        // )
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
