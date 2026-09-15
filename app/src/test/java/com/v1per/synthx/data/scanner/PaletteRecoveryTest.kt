package com.v1per.synthx.data.scanner

import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.repository.SongPaletteSwatch
import com.v1per.synthx.data.repository.toPaletteSwatches
import com.v1per.synthx.ui.components.ExtractedSwatchHsl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PaletteRecoveryTest {
    private val song = SongEntity(
        id = "song", title = "Title", albumId = null, duration = 1000L,
        filePath = "content://audio/song", albumArtUri = "compressed", year = 2026,
        dateAdded = 123L, albumArtFullUri = "full", isStarred = false,
    )
    private val swatches = listOf(
        ExtractedSwatchHsl(10f, 0.2f, 0.3f),
        ExtractedSwatchHsl(120f, 0.4f, 0.5f),
        ExtractedSwatchHsl(240f, 0.6f, 0.7f),
    )

    @Test fun includesEitherArtVariantButExcludesMissingAudioNoArtAndAlreadyPopulatedRows() {
        val rows = listOf(
            song.copy(id = "compressed", albumArtFullUri = null),
            song.copy(id = "full", albumArtUri = null),
            song.copy(id = "missing", isMissing = true),
            song.copy(id = "no-art", albumArtUri = null, albumArtFullUri = null),
            PaletteRecovery.mergePaletteRecoveryResult(song.copy(id = "complete"), swatches),
        )
        assertEquals(listOf("compressed", "full"), PaletteRecovery.findMissingPalette(rows).map { it.id })
    }

    @Test fun anyMissingComponentIsEligibleAndOnlyACompletePaletteIsExcluded() {
        val complete = PaletteRecovery.mergePaletteRecoveryResult(song, swatches)
        val partials = listOf(
            complete.copy(swatch1Hue = null),
            complete.copy(swatch1Saturation = null),
            complete.copy(swatch1Lightness = null),
            complete.copy(swatch2Hue = null),
            complete.copy(swatch2Saturation = null),
            complete.copy(swatch2Lightness = null),
            complete.copy(swatch3Hue = null),
            complete.copy(swatch3Saturation = null),
            complete.copy(swatch3Lightness = null),
        )
        assertEquals(partials, PaletteRecovery.findMissingPalette(partials))
        assertEquals(emptyList(), PaletteRecovery.findMissingPalette(listOf(complete)))
    }

    @Test fun failedOrIncompleteExtractionPreservesEveryExistingValue() {
        val current = song.copy(swatch2Hue = 123f)
        listOf(null, emptyList(), swatches.take(1), swatches.take(2), swatches + swatches[0]).forEach {
            assertSame(current, PaletteRecovery.mergePaletteRecoveryResult(current, it))
        }
    }

    @Test fun successfulMergeKeepsRawOrderAndAllNonPaletteFields() {
        val updated = PaletteRecovery.mergePaletteRecoveryResult(song, swatches)
        assertEquals(swatches.map { SongPaletteSwatch(it.hue, it.saturation, it.lightness) }, updated.toPaletteSwatches())
        assertEquals(song, updated.copy(
            swatch1Hue = null, swatch1Saturation = null, swatch1Lightness = null,
            swatch2Hue = null, swatch2Saturation = null, swatch2Lightness = null,
            swatch3Hue = null, swatch3Saturation = null, swatch3Lightness = null,
        ))
    }
}
