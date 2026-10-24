package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coloured-pixel coverage for [backgroundBrightness]. Grey pixels only pin the sum of the channel weights;
 * these inputs make R, G and B differ so each weight is pinned on its own.
 *
 * Expected values are computed outside the code under test, per pixel:
 * 1. saturation stage: out = M * rgb, with M = lerp(luma row, identity row, saturation), luma = (0.213, 0.715, 0.072),
 *    each output clamped to 0..1;
 * 2. each output converted from sRGB to linear (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055)^2.4);
 * 3. luminance = 0.2126 * R + 0.7152 * G + 0.0722 * B.
 * Saturation 1 makes M the identity, so only step 3 weights apply. Saturation 0 collapses every output to the
 * luma value, so the saturation-stage luma weights are pinned there.
 * Tolerance is 1e-5: the arithmetic is Float, with errors around 1e-7.
 */
class PlayerBackgroundLuminanceChannelWeightsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val tolerance = 1e-5f

    private fun measure(vararg pixels: Int, saturation: Float = 1f): Float =
        requireNotNull(backgroundBrightness(pixels, saturation)) { "expected a value for ${pixels.size} pixels" }

    private fun assertBrightness(expected: Float, pixel: Int, saturation: Float = 1f) {
        assertEquals(
            expected,
            measure(pixel, saturation = saturation),
            tolerance,
            "pixel 0x${pixel.toUInt().toString(16)}",
        )
    }

    @Test fun redDominantPixelPinsTheRedAndBlueWeights() {
        // 0.2126 * lin(0x80) + (0.7152 + 0.0722) * lin(0x40) = 0.086262
        assertBrightness(0.086262f, 0xFF804040.toInt())
    }

    @Test fun greenDominantPixelPinsTheGreenWeight() {
        // 0.7152 * lin(0x80) + (0.2126 + 0.0722) * lin(0x40) = 0.168985
        assertBrightness(0.168985f, 0xFF408040.toInt())
    }

    @Test fun blueDominantPixelPinsTheBlueWeight() {
        // 0.0722 * lin(0x80) + (0.2126 + 0.7152) * lin(0x40) = 0.063153
        assertBrightness(0.063153f, 0xFF404080.toInt())
    }

    @Test fun saturatedPrimariesYieldTheirChannelWeights() {
        assertBrightness(0.2126f, 0xFFFF0000.toInt())
        assertBrightness(0.7152f, 0xFF00FF00.toInt())
        assertBrightness(0.0722f, 0xFF0000FF.toInt())
    }

    @Test fun singleChannelHalfIntensityScalesEachWeightByTheLinearValue() {
        // weight * lin(0x80), lin(0x80) = 0.215861
        assertBrightness(0.045892f, 0xFF800000.toInt())
        assertBrightness(0.154383f, 0xFF008000.toInt())
        assertBrightness(0.015585f, 0xFF000080.toInt())
    }

    @Test fun mixedChannelsAddTheirWeightedLinearValues() {
        // 0.2126 * 1 + 0.7152 * lin(0x80), and 0.7152 + 0.0722 for green plus blue
        assertBrightness(0.366983f, 0xFFFF8000.toInt())
        assertBrightness(0.7874f, 0xFF00FFFF.toInt())
    }

    @Test fun equalChannelMassRanksGreenAboveRedAboveBlue() {
        val red = measure(0xFF800000.toInt())
        val green = measure(0xFF008000.toInt())
        val blue = measure(0xFF000080.toInt())
        assertTrue(green > red, "green $green should exceed red $red")
        assertTrue(red > blue, "red $red should exceed blue $blue")
    }

    @Test fun productionSaturationKeepsEachChannelWeightDistinct() {
        // Saturation 1.75 pushes channels apart and clamps some to 0..1 before the sRGB step.
        assertBrightness(0.109646f, 0xFF804040.toInt(), saturation = 1.75f)
        assertBrightness(0.196143f, 0xFF408040.toInt(), saturation = 1.75f)
        assertBrightness(0.072662f, 0xFF404080.toInt(), saturation = 1.75f)
        assertBrightness(0.127742f, 0xFF800000.toInt(), saturation = 1.75f)
        assertBrightness(0.235627f, 0xFF008000.toInt(), saturation = 1.75f)
        assertBrightness(0.050143f, 0xFF000080.toInt(), saturation = 1.75f)
    }

    @Test fun zeroSaturationPinsTheSaturationStageLumaWeights() {
        // Every output channel equals 0.213 R + 0.715 G + 0.072 B, then the same sRGB and WCAG steps.
        assertBrightness(0.037301f, 0xFFFF0000.toInt(), saturation = 0f)
        assertBrightness(0.469647f, 0xFF00FF00.toInt(), saturation = 0f)
        assertBrightness(0.006213f, 0xFF0000FF.toInt(), saturation = 0f)
        assertBrightness(0.075456f, 0xFF804040.toInt(), saturation = 0f)
        assertBrightness(0.155203f, 0xFF408040.toInt(), saturation = 0f)
        assertBrightness(0.058839f, 0xFF404080.toInt(), saturation = 0f)
    }

    @Test fun medianIsTakenOverWeightedLuminanceNotRawChannels() {
        // Luminances: blue 0.0722, 804040 0.086262, 408040 0.168985, red 0.2126, green 0.7152.
        // The per-channel median is 0x404040 (0.051269), which no luminance ordering produces.
        val mixed = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFF804040.toInt(),
            0xFF408040.toInt(),
        )
        assertEquals(0.168985f, measure(*mixed), tolerance)
    }

    @Test fun outliersDoNotMoveTheColouredMedian() {
        val core = intArrayOf(
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFF804040.toInt(),
            0xFF408040.toInt(),
        )
        val withOutliers = core + intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(measure(*core), measure(*withOutliers), tolerance)
        assertEquals(0.168985f, measure(*withOutliers), tolerance)
    }
}
