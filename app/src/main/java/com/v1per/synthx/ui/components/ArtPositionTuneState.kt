package com.v1per.synthx.ui.components

import androidx.compose.runtime.mutableStateOf

internal data class ArtPositionTuneSettings(
    // Fraction of leftover flexible space above the art; the rest is split around the title.
    val aboveFraction: Float = 0.6f,
    // 1 keeps the original art-to-title gap; 0 moves all of it below the title instead.
    val titleTightnessFraction: Float = 1.0f,
)

internal object ArtPositionTuneStateHolder {
    val state = mutableStateOf(ArtPositionTuneSettings())
}
