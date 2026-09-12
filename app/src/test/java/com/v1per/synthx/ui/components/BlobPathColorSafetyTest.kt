package com.v1per.synthx.ui.components

import androidx.compose.ui.graphics.Color
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.repository.toPaletteSwatches
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * End-to-end proof that an out-of-range persisted palette (a hand-edited row, or a restore from
 * a divergent writer) survives the real production chain - [SongEntity.toPaletteSwatches],
 * [deriveBlobColors], [DerivedBlobColors.applyTo] - without a downstream [Color.hsl] call ever
 * seeing a value outside what it requires (0f..360f hue, 0f..1f saturation/lightness).
 */
class BlobPathColorSafetyTest {
    @Test fun outOfRangePersistedPaletteNeverReachesColorHslOutOfBounds() {
        val song = SongEntity(
            id = "song", title = "Song", albumId = null, duration = 1000,
            filePath = "audio", albumArtUri = "art", year = null, dateAdded = 1,
            swatch1Hue = 400f, swatch1Saturation = 1.5f, swatch1Lightness = -0.2f,
            swatch2Hue = -10f, swatch2Saturation = -0.5f, swatch2Lightness = 1.8f,
            swatch3Hue = 720f, swatch3Saturation = 2f, swatch3Lightness = -1f,
        )
        val paletteSwatches = assertNotNull(song.toPaletteSwatches())
        val extracted = paletteSwatches.map { ExtractedSwatchHsl(it.hue, it.saturation, it.lightness) }
        val colors = assertNotNull(deriveBlobColors(extracted))
        val blobs = colors.applyTo(DefaultInitialBlobs)

        // Would throw IllegalArgumentException if any component were still out of range.
        Color.hsl(colors.baseSurfaceHue, BaseSurfaceSaturation, BaseSurfaceLightness)
        blobs.forEach { blob -> Color.hsl(blob.hue, blob.saturation, blob.lightness) }
    }
}
