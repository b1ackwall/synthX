package com.v1per.synthx.ui.components

internal const val BaseSurfaceSaturation = 0.25f
internal const val BaseSurfaceLightness = 0.1f

// Default blob parameters for the Home background and its Developer Settings controls.
internal val DefaultInitialBlobs = listOf(
    BlobParams(sizeFraction = 1.0f, opacity = 0.4f, gradientFalloffFraction = 0.25f),
    BlobParams(sizeFraction = 0.8f, opacity = 0.6f, gradientFalloffFraction = 0.5f),
    BlobParams(sizeFraction = 0.6f, opacity = 0.8f, gradientFalloffFraction = 0.75f),
)
