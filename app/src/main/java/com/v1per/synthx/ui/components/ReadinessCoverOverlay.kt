package com.v1per.synthx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Covers a destination's content with solid black until its data is ready, so a fresh
// navigation cuts straight to a finished screen instead of a Loading flash. Hard cut, no
// fade — content is either fully hidden or fully shown, matching the app-wide
// instant-transition convention.
@Composable
fun ReadinessCoverOverlay(isReady: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize()) {
        if (isReady) {
            content()
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        }
    }
}
