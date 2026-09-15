package com.v1per.synthx.ui.components

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        val doubledIndex = colors.arrangement.swatchIndicesBySlot
            .groupingBy { it }.eachCount().entries.single { it.value == 2 }.key

        assertSame(swatches, colors.artworkSwatches)
        assertEquals(3, updated.size)
        updated.forEachIndexed { index, blob ->
            val swatch = swatches.single { it.hue == blob.hue }
            val swatchIndex = colors.arrangement.swatchIndicesBySlot[index + 1]
            // R5-F12 damping: the render slot sharing the arrangement's doubled swatch index
            // gets an extra opacity reduction so a mono-hue song doesn't blow out under
            // BlendMode.Plus; every other slot keeps the plain lightness-derived value.
            val expectedOpacity = (1f - swatch.lightness).let {
                if (swatchIndex == doubledIndex) it * 0.6f else it
            }
            assertEquals(swatch.saturation, blob.saturation)
            assertEquals(swatch.lightness, blob.lightness)
            assertEquals(expectedOpacity, blob.opacity)
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
    fun `dark tones retain full opacity with the separate falloff ceiling, damped only on the doubled slot`() {
        // Three identical swatches is exactly R5-F12's mono-hue fallback shape (see
        // AlbumArtPaletteExtraction's threshold-exhaustion path), so the doubled render slot
        // here also picks up the mono-hue opacity damping on top of the lightness formula.
        // generateBlobColorArrangement(Random(0)) reproduces the same arrangement deriveBlobColors
        // computes internally, since both start from a fresh Random(0) seed.
        val doubledIndex = requireNotNull(
            generateBlobColorArrangement(Random(0)).swatchIndicesBySlot
                .groupingBy { it }.eachCount().entries.singleOrNull { it.value == 2 }?.key,
        )
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
            val arrangement = colors.arrangement.swatchIndicesBySlot.drop(1)
            val expectedOpacities = arrangement.map { swatchIndex ->
                if (swatchIndex == doubledIndex) expectedOpacity * 0.6f else expectedOpacity
            }
            assertEquals(expectedOpacities, colors.blobs.map { it.opacity })
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

    @Test
    fun `mono-hue fallback damps the doubled slot while the multi-hue case is untouched`() {
        // R5-F12 regression protection: AlbumArtPaletteExtraction's fallback can return 3
        // identical swatches for genuinely mono-hue artwork (see its threshold-exhaustion
        // path) — verified here the same way the existing "dark tones" test verifies the
        // lightness formula, since no mono-cover test image exists in the project.
        val monoSwatches = List(3) { ExtractedSwatchHsl(200f, 0.6f, 0.4f) }
        val multiSwatches = listOf(
            ExtractedSwatchHsl(30f, 0.7f, 0.4f),
            ExtractedSwatchHsl(150f, 0.6f, 0.4f),
            ExtractedSwatchHsl(270f, 0.5f, 0.4f),
        )
        val undampedOpacity = (1f - 0.4f)

        val monoColors = assertNotNull(deriveBlobColors(monoSwatches, Random(0)))
        val monoDoubledIndex = monoColors.arrangement.swatchIndicesBySlot
            .groupingBy { it }.eachCount().entries.single { it.value == 2 }.key
        monoColors.arrangement.swatchIndicesBySlot.drop(1).forEachIndexed { blobIndex, swatchIndex ->
            val opacity = monoColors.blobs[blobIndex].opacity
            if (swatchIndex == monoDoubledIndex) {
                assertTrue(
                    opacity < undampedOpacity,
                    "Doubled slot's opacity ($opacity) must be damped below $undampedOpacity",
                )
            } else {
                assertEquals(undampedOpacity, opacity)
            }
        }

        // Multi-hue case: only the swatch that legitimately doubles across four slots is
        // damped — this is unchanged from the pre-existing structural-doubling behavior
        // ("assigned swatches..." test above already pins the exact damped value).
        val multiColors = assertNotNull(deriveBlobColors(multiSwatches, Random(0)))
        val multiDoubledIndex = multiColors.arrangement.swatchIndicesBySlot
            .groupingBy { it }.eachCount().entries.single { it.value == 2 }.key
        multiColors.arrangement.swatchIndicesBySlot.drop(1).forEachIndexed { blobIndex, swatchIndex ->
            val expectedSwatchLightness = multiSwatches[swatchIndex].lightness
            val expected = (1f - expectedSwatchLightness).let {
                if (swatchIndex == multiDoubledIndex) it * 0.6f else it
            }
            assertEquals(expected, multiColors.blobs[blobIndex].opacity)
        }
    }
}
