package com.v1per.synthx.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class BlobColorDerivationTest {
    @Test
    fun `empty extraction leaves the caller without an update`() {
        assertNull(deriveBlobColors(emptyList()))
    }

    @Test
    fun `palette order colors the base and corresponding slots without changing tuning`() {
        val swatches = listOf(
            ExtractedSwatchHsl(30f, 0.7f, 0.25f),
            ExtractedSwatchHsl(150f, 0.4f, 0.5f),
            ExtractedSwatchHsl(270f, 0.9f, 0.75f),
        )
        val current = DefaultInitialBlobs.mapIndexed { index, blob ->
            blob.copy(
                enabled = index != 1,
                speedFraction = 0.2f,
                pointCount = 8,
                wobbleAmountFraction = 0.3f,
                wobbleSpeed = 0.6f,
                opacityRandomizer = RandomizerConfig(enabled = true),
            )
        }
        val colors = assertNotNull(deriveBlobColors(swatches))
        val updated = colors.applyTo(current)

        assertEquals(30f, colors.baseSurfaceHue)
        assertEquals(listOf(30f, 150f, 270f), updated.map { it.hue })
        assertEquals(listOf(0.7f, 0.4f, 0.9f), updated.map { it.saturation })
        assertEquals(listOf(0.25f, 0.5f, 0.75f), updated.map { it.lightness })
        assertEquals(listOf(0.75f, 0.5f, 0.25f), updated.map { it.opacity })
        assertEquals(listOf(0.75f, 0.5f, 0.25f), updated.map { it.gradientFalloffFraction })
        updated.forEachIndexed { index, blob ->
            val original = current[index]
            assertEquals(
                original,
                blob.copy(
                    hue = original.hue,
                    saturation = original.saturation,
                    lightness = original.lightness,
                    opacity = original.opacity,
                    gradientFalloffFraction = original.gradientFalloffFraction,
                ),
            )
        }
    }

    @Test
    fun `dark tones retain full opacity with the separate falloff ceiling`() {
        val colors = assertNotNull(deriveBlobColors(
            listOf(-0.1f, 0f, 1f, 1.1f).map { ExtractedSwatchHsl(125f, 0.8f, it) },
        ))
        assertEquals(listOf(1f, 1f, 0f, 0f), colors.blobs.map { it.opacity })
        assertEquals(listOf(0.9f, 0.9f, 0f, 0f), colors.blobs.map { it.gradientFalloffFraction })
    }

    @Test
    fun `partial extraction preserves unmatched slots and their references`() {
        val colors = assertNotNull(deriveBlobColors(listOf(ExtractedSwatchHsl(42f, 0.2f, 0.5f))))
        val updated = colors.applyTo(DefaultInitialBlobs)
        assertEquals(42f, updated[0].hue)
        assertSame(DefaultInitialBlobs[1], updated[1])
        assertSame(DefaultInitialBlobs[2], updated[2])
        assertEquals(3, updated.size)
    }
}
