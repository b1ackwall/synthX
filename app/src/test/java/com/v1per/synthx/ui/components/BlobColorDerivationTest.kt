package com.v1per.synthx.ui.components

import kotlin.random.Random
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
    fun `assigned swatches retain their color derivation without changing slot tuning`() {
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
        val colors = assertNotNull(deriveBlobColors(swatches, Random(0)))
        val updated = colors.applyTo(current)

        assertSame(swatches, colors.artworkSwatches)
        assertEquals(3, updated.size)
        updated.forEachIndexed { index, blob ->
            val swatch = swatches.single { it.hue == blob.hue }
            assertEquals(swatch.saturation, blob.saturation)
            assertEquals(swatch.lightness, blob.lightness)
            assertEquals(1f - swatch.lightness, blob.opacity)
            assertEquals(1f - swatch.lightness, blob.gradientFalloffFraction)
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
        for ((lightness, expectedOpacity, expectedFalloff) in listOf(
            Triple(-0.1f, 1f, 0.9f),
            Triple(0f, 1f, 0.9f),
            Triple(1f, 0f, 0f),
            Triple(1.1f, 0f, 0f),
        )) {
            val colors = assertNotNull(deriveBlobColors(
                List(3) { ExtractedSwatchHsl(125f, 0.8f, lightness) },
                Random(0),
            ))
            assertEquals(List(3) { expectedOpacity }, colors.blobs.map { it.opacity })
            assertEquals(List(3) { expectedFalloff }, colors.blobs.map { it.gradientFalloffFraction })
        }
    }

    @Test
    fun `incomplete or oversized extraction leaves the caller without an update`() {
        for (size in listOf(1, 2, 4)) {
            assertNull(deriveBlobColors(List(size) { ExtractedSwatchHsl(42f, 0.2f, 0.5f) }))
        }
    }

    @Test
    fun `random assignments use every swatch with exactly one doubled across four slots`() {
        val swatches = listOf(
            ExtractedSwatchHsl(30f, 0.7f, 0.25f),
            ExtractedSwatchHsl(150f, 0.4f, 0.5f),
            ExtractedSwatchHsl(270f, 0.9f, 0.75f),
        )
        val expectedHues = swatches.map { it.hue }.toSet()
        val doubledHues = mutableSetOf<Float>()
        val huesBySlot = List(4) { mutableSetOf<Float>() }
        repeat(100) { seed ->
            val colors = assertNotNull(deriveBlobColors(swatches, Random(seed)))
            val slotHues = listOf(colors.baseSurfaceHue) + colors.blobs.map { it.hue }
            val counts = slotHues.groupingBy { it }.eachCount()
            assertEquals(expectedHues, counts.keys)
            assertEquals(listOf(1, 1, 2), counts.values.sorted())
            assertSame(swatches, colors.artworkSwatches)
            doubledHues += counts.entries.single { it.value == 2 }.key
            slotHues.forEachIndexed { index, hue -> huesBySlot[index] += hue }
        }
        assertEquals(expectedHues, doubledHues)
        huesBySlot.forEach { assertEquals(expectedHues, it) }
    }
}
