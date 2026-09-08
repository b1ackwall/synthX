package com.v1per.synthx.ui.components

import androidx.compose.runtime.mutableStateOf

// Shared by Home's background renderer and the controls in Developer Settings.
internal data class BlobBackgroundDebugState(
    // Size/opacity randomizers stay disabled; these are the editable testbed defaults.
    val blobs: List<BlobParams> = DefaultInitialBlobs,
    val baseSurfaceHue: Float = 125f,
    val blurRadiusDp: Float = 40f,
)

// Process-lifetime testbed state; no preferences to hydrate or Application setup needed.
internal object HomeBlobDebugStateHolder {
    val state = mutableStateOf(BlobBackgroundDebugState())
    var lastPaletteSongId: String? = null
}
