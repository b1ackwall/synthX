package com.v1per.synthx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.v1per.synthx.data.repository.Song
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
    val arrangement: BlobColorArrangement,
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

/** Four render slots (base surface, then three blobs) expressed as raw swatch indices. */
internal data class BlobColorArrangement(val swatchIndicesBySlot: List<Int>)

/**
 * Stable per-song arrangement for the shared Home/player background. The caller still remembers
 * this by song id so one object is retained while same-song palette values are backfilled.
 */
internal fun generateBlobColorArrangement(songId: String): BlobColorArrangement =
    generateBlobColorArrangement(Random(songId.hashCode()))

internal fun generateBlobColorArrangement(random: Random): BlobColorArrangement {
    val doubledIndex = random.nextInt(3)
    return BlobColorArrangement(
        (listOf(doubledIndex, doubledIndex) + (0 until 3).filter { it != doubledIndex })
            .shuffled(random),
    )
}

/** Applies the latest swatch values without changing their already-chosen render slots. */
// R5-F12: the doubled swatch fills two of the four render slots (base + 3 blobs). When
// extraction falls back to 3 identical swatches (mono-hue artwork, see
// AlbumArtPaletteExtraction's threshold exhaustion path), ALL four slots share one hue/
// saturation/lightness — so two (or all three) same-hue blobs stack under BlendMode.Plus and
// clip toward white where they overlap, worse than any genuinely multi-hue overlap. Damping
// only the slot(s) built from the doubled swatch index keeps the ordinary multi-hue case (where
// no index repeats) completely unaffected.
private const val DoubledSlotOpacityDampingFactor = 0.6f

/**
 * Deliberate correlation, not an oversight: opacity and falloff are both driven by
 * `1f - lightness` so a swatch's intensity and edge hardness move together — the more opaque a
 * blob is, the harder its edge, and vice versa. `BlobColorDerivationTest`'s "dark tones" test
 * locks this relationship in. Falloff keeps a separate, lower ceiling (0.9 vs 1.0) so even the
 * darkest source color never renders as a fully hard-edged disc.
 */
internal fun applyBlobColorArrangement(
    arrangement: BlobColorArrangement,
    swatches: List<ExtractedSwatchHsl>,
): DerivedBlobColors? {
    if (swatches.size != 3) return null
    val slots = arrangement.swatchIndicesBySlot.map { index -> swatches.getOrNull(index) ?: return null }
    if (slots.size != 4) return null
    val doubledIndex = arrangement.swatchIndicesBySlot
        .groupingBy { it }.eachCount()
        .entries.singleOrNull { it.value == 2 }?.key
    return DerivedBlobColors(
        arrangement = arrangement,
        baseSurfaceHue = slots[0].hue,
        blobs = arrangement.swatchIndicesBySlot.drop(1).zip(slots.drop(1)) { swatchIndex, swatch ->
            // Additive blending blows out light colors, so intensity scales inversely
            // with each swatch's lightness. Falloff has a separate, lower ceiling.
            val baseOpacity = (1f - swatch.lightness).coerceIn(0f, 1f)
            val opacity = if (swatchIndex == doubledIndex) {
                baseOpacity * DoubledSlotOpacityDampingFactor
            } else {
                baseOpacity
            }
            BlobColorUpdate(
                hue = swatch.hue,
                saturation = swatch.saturation,
                lightness = swatch.lightness,
                opacity = opacity,
                gradientFalloffFraction = (1f - swatch.lightness).coerceIn(0f, 0.9f),
            )
        },
        artworkSwatches = swatches,
    )
}

/** One derivation for the shell to share with every surface rendering the current song. */
@Composable
internal fun rememberSharedSongBlobColors(song: Song?): DerivedBlobColors? {
    val arrangement = remember(song?.id) {
        song?.id?.let(::generateBlobColorArrangement)
    }
    return remember(arrangement, song?.paletteSwatches) {
        val stableArrangement = arrangement ?: return@remember null
        val swatches = song?.paletteSwatches ?: return@remember null
        applyBlobColorArrangement(
            stableArrangement,
            swatches.map { ExtractedSwatchHsl(it.hue, it.saturation, it.lightness) },
        )
    }
}

internal fun deriveBlobColors(
    swatches: List<ExtractedSwatchHsl>,
    random: Random = Random.Default,
): DerivedBlobColors? {
    if (swatches.size != 3) return null
    return applyBlobColorArrangement(generateBlobColorArrangement(random), swatches)
}
