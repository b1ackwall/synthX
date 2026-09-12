package com.v1per.synthx.data.repository

import androidx.compose.runtime.Immutable
import com.v1per.synthx.data.db.SongEntity

@Immutable
data class SongPaletteSwatch(val hue: Float, val saturation: Float, val lightness: Float)

/** Partial rows never reach rendering as a partial palette. */
internal fun SongEntity.toPaletteSwatches(): List<SongPaletteSwatch>? {
    val swatch1Hue = swatch1Hue ?: return null
    val swatch1Saturation = swatch1Saturation ?: return null
    val swatch1Lightness = swatch1Lightness ?: return null
    val swatch2Hue = swatch2Hue ?: return null
    val swatch2Saturation = swatch2Saturation ?: return null
    val swatch2Lightness = swatch2Lightness ?: return null
    val swatch3Hue = swatch3Hue ?: return null
    val swatch3Saturation = swatch3Saturation ?: return null
    val swatch3Lightness = swatch3Lightness ?: return null
    return listOf(
        clampedPaletteSwatch(swatch1Hue, swatch1Saturation, swatch1Lightness),
        clampedPaletteSwatch(swatch2Hue, swatch2Saturation, swatch2Lightness),
        clampedPaletteSwatch(swatch3Hue, swatch3Saturation, swatch3Lightness),
    )
}

/**
 * Coerces persisted (or hand-edited/restored) HSL components into the range [Color.hsl]
 * requires, so nothing downstream needs to re-check values that already came from storage.
 */
private fun clampedPaletteSwatch(hue: Float, saturation: Float, lightness: Float) =
    SongPaletteSwatch(
        hue = hue.coerceIn(0f, 360f),
        saturation = saturation.coerceIn(0f, 1f),
        lightness = lightness.coerceIn(0f, 1f),
    )
