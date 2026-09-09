package com.v1per.synthx.ui.components

import android.content.Context
import kotlin.random.Random

internal const val BaseSurfaceSaturation = 0.25f
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
    // Original extraction order, retained for consumers such as the player's locked scrim.
    val artworkSwatches: List<ExtractedSwatchHsl>,
) {
    // Apply assigned colors in slot order, preserving every other tuned parameter.
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

internal suspend fun resolveArtworkBackground(context: Context, uri: String): DerivedBlobColors? =
    deriveBlobColors(extractArtworkSwatches(context, uri))

internal fun deriveBlobColors(
    swatches: List<ExtractedSwatchHsl>,
    random: Random = Random.Default,
): DerivedBlobColors? {
    if (swatches.size != 3) return null
    // NOTE: Random assignment is recomputed on every extraction call (i.e. every song change during live playback). A future scan-time-extraction migration must decide and PERSIST this random assignment once, at scan time — not re-randomize on every playback, since there will be no live re-extraction step to trigger it.
    val doubledIndex = random.nextInt(swatches.size)
    // Select by index: the extractor may reuse colors when artwork has a limited palette.
    val slots = (listOf(swatches[doubledIndex], swatches[doubledIndex]) +
        swatches.filterIndexed { index, _ -> index != doubledIndex }).shuffled(random)
    return DerivedBlobColors(
        baseSurfaceHue = slots[0].hue,
        blobs = slots.drop(1).map { swatch ->
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
        artworkSwatches = swatches,
    )
}
