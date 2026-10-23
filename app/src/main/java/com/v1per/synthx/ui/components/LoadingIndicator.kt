package com.v1per.synthx.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.v1per.synthx.ui.theme.RingSizes
import com.v1per.synthx.ui.theme.Strokes
import com.v1per.synthx.ui.theme.TextSecondary

/**
 * Shared full-bleed loading state for [com.v1per.synthx.ui.common.UiState.Loading]. Neutral
 * (not Accent) on purpose — Accent is locked to five specific contexts and a loading spinner
 * isn't one of them.
 */
@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(RingSizes.Large),
            color = TextSecondary,
            strokeWidth = Strokes.ProgressRing,
        )
    }
}
