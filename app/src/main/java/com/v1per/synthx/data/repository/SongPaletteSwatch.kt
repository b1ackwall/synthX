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
        SongPaletteSwatch(swatch1Hue, swatch1Saturation, swatch1Lightness),
        SongPaletteSwatch(swatch2Hue, swatch2Saturation, swatch2Lightness),
        SongPaletteSwatch(swatch3Hue, swatch3Saturation, swatch3Lightness),
    )
}
