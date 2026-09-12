package com.v1per.synthx.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumArtPaletteExtractionTest {
    @Test fun inRangeComponentsPassThroughUnchanged() {
        assertEquals(
            ExtractedSwatchHsl(125f, 0.8f, 0.5f),
            clampedExtractedSwatch(125f, 0.8f, 0.5f),
        )
    }

    @Test fun outOfRangeComponentsAreClampedToWhatColorHslAccepts() {
        assertEquals(ExtractedSwatchHsl(360f, 1f, 0f), clampedExtractedSwatch(400f, 1.5f, -0.2f))
        assertEquals(ExtractedSwatchHsl(0f, 0f, 1f), clampedExtractedSwatch(-10f, -0.5f, 1.8f))
    }
}
