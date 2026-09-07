package com.v1per.synthx.ui.components

import androidx.compose.runtime.mutableStateOf

// Shared by Home's background renderer and the controls in Developer Settings.
internal data class BlobBackgroundDebugState(
    // Size/opacity randomizers stay disabled; these are the editable testbed defaults.
    val blobs: List<BlobParams> = listOf(
        BlobParams(sizeFraction = 1.0f, opacity = 0.4f, gradientFalloffFraction = 0.25f),
        BlobParams(sizeFraction = 0.8f, opacity = 0.6f, gradientFalloffFraction = 0.5f),
        BlobParams(sizeFraction = 0.6f, opacity = 0.8f, gradientFalloffFraction = 0.75f),
    ),
    val baseSurfaceHue: Float = 125f,
    val blurRadiusDp: Float = 40f,
) {
    val baseSurfaceSaturation: Float = 0.5f
    val baseSurfaceLightness: Float = 0.1f
}

// Process-lifetime testbed state; no preferences to hydrate or Application setup needed.
internal object HomeBlobDebugStateHolder {
    val state = mutableStateOf(BlobBackgroundDebugState())
    var lastPaletteSongId: String? = null
}
