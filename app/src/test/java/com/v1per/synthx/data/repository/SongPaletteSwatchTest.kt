package com.v1per.synthx.data.repository

import com.v1per.synthx.data.db.SongEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongPaletteSwatchTest {
    private val complete = SongEntity(
        id = "song", title = "Song", albumId = null, duration = 1000,
        filePath = "audio", albumArtUri = "art", year = null, dateAdded = 1,
        swatch1Hue = 10f, swatch1Saturation = 0.1f, swatch1Lightness = 0.2f,
        swatch2Hue = 120f, swatch2Saturation = 0.3f, swatch2Lightness = 0.4f,
        swatch3Hue = 240f, swatch3Saturation = 0.5f, swatch3Lightness = 0.6f,
    )

    @Test fun mapsAllThreeRawSwatchesInStoredOrder() {
        assertEquals(
            listOf(
                SongPaletteSwatch(10f, 0.1f, 0.2f),
                SongPaletteSwatch(120f, 0.3f, 0.4f),
                SongPaletteSwatch(240f, 0.5f, 0.6f),
            ),
            complete.toPaletteSwatches(),
        )
    }

    @Test fun anyMissingComponentMakesTheEntirePaletteUnavailable() {
        listOf(
            complete.copy(swatch1Hue = null),
            complete.copy(swatch1Saturation = null),
            complete.copy(swatch1Lightness = null),
            complete.copy(swatch2Hue = null),
            complete.copy(swatch2Saturation = null),
            complete.copy(swatch2Lightness = null),
            complete.copy(swatch3Hue = null),
            complete.copy(swatch3Saturation = null),
            complete.copy(swatch3Lightness = null),
        ).forEach { assertNull(it.toPaletteSwatches()) }
    }

    @Test fun reusedSwatchesArePreservedAndNotDeduplicated() {
        val repeated = complete.copy(
            swatch2Hue = 10f, swatch2Saturation = 0.1f, swatch2Lightness = 0.2f,
            swatch3Hue = 10f, swatch3Saturation = 0.1f, swatch3Lightness = 0.2f,
        )
        assertEquals(List(3) { SongPaletteSwatch(10f, 0.1f, 0.2f) }, repeated.toPaletteSwatches())
    }
}
