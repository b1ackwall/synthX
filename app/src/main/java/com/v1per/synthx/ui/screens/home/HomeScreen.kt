package com.v1per.synthx.ui.screens.home

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tracing.Trace
import com.v1per.synthx.BuildConfig
import com.v1per.synthx.perf.PerfTestTags
import com.v1per.synthx.perf.PerfTraceSections
import com.v1per.synthx.ui.components.Wordmark
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.Spacing

// Body is bracketed by begin/endSection (PerfTraceSections); try/finally cannot wrap composables.
@SuppressLint("UnclosedTrace")
@Composable
internal fun HomeScreen(viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory())) {
    Trace.beginSection(PerfTraceSections.HomeScreen)
    // Solid app base surface, matching the other tabs' Background.
    Box(Modifier.fillMaxSize().testTag(PerfTestTags.HomeScreen).background(Background)) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Wordmark(heightStyle = MaterialTheme.typography.titleLarge)
            }
        }
        if (BuildConfig.DEBUG) PlayerBackgroundDebugControls()
    }
    Trace.endSection()
}
