package com.v1per.synthx.ui.components

internal const val BaseSurfaceSaturation = 0.5f
internal const val BaseSurfaceLightness = 0.1f

// Shared starting tones; palette updates preserve each slot's other tuned parameters.
internal val DefaultInitialBlobs = listOf(
    BlobParams(sizeFraction = 1.0f, opacity = 0.4f, gradientFalloffFraction = 0.25f),
    BlobParams(sizeFraction = 0.8f, opacity = 0.6f, gradientFalloffFraction = 0.5f),
    BlobParams(sizeFraction = 0.6f, opacity = 0.8f, gradientFalloffFraction = 0.75f),
)

internal data class DerivedBlobColors(
    val baseSurfaceHue: Float,
    val blobs: List<BlobColorUpdate>,
) {
    // Swatch 0 colors the base and Blob 1; subsequent swatches follow slot order.
    // Missing swatches retain the slot's current values, including debug edits.
    fun applyTo(currentBlobs: List<BlobParams>): List<BlobParams> =
        currentBlobs.mapIndexed { index, blob ->
            blobs.getOrNull(index)?.let { update ->
                blob.copy(
                    hue = update.hue,
                    saturation = update.saturation,
                    lightness = update.lightness,
                    opacity = update.opacity,
                    gradientFalloffFraction = update.gradientFalloffFraction,
                )
            } ?: blob
        }
}

internal data class BlobColorUpdate(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val opacity: Float,
    val gradientFalloffFraction: Float,
)

internal fun deriveBlobColors(swatches: List<ExtractedSwatchHsl>): DerivedBlobColors? {
    if (swatches.isEmpty()) return null
    return DerivedBlobColors(
        baseSurfaceHue = swatches[0].hue,
        blobs = swatches.map { swatch ->
            // Additive blending blows out light colors, so intensity scales inversely
            // with each swatch's lightness. Falloff has a separate, lower ceiling.
            BlobColorUpdate(
                hue = swatch.hue,
                saturation = swatch.saturation,
                lightness = swatch.lightness,
                opacity = (1f - swatch.lightness).coerceIn(0f, 1f),
                gradientFalloffFraction = (1f - swatch.lightness).coerceIn(0f, 0.9f),
            )
        },
    )
}
