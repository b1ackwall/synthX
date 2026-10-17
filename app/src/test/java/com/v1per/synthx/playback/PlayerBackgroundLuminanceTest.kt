package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerBackgroundLuminanceTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun measure(pixels: IntArray, saturation: Float = 1f) =
        assertNotNull(backgroundBrightness(pixels, saturation))

    private fun assertAll(expected: Float, stats: BrightnessStats, tolerance: Float) {
        assertEquals(expected, stats.mean, tolerance)
        assertEquals(expected, stats.median, tolerance)
        assertEquals(expected, stats.p90, tolerance)
    }

    @Test fun allWhiteGivesOneForBothMetricsAndAllStatistics() {
        val result = measure(IntArray(16) { white }, saturation = 1.75f)
        assertAll(1f, result.wcag, 1e-4f)
        assertAll(1f, result.oklab, 1e-4f)
    }

    @Test fun allBlackGivesZero() {
        val result = measure(IntArray(16) { black }, saturation = 1.75f)
        assertAll(0f, result.wcag, 1e-6f)
        assertAll(0f, result.oklab, 1e-6f)
    }

    @Test fun noPixelsYieldsNoValue() {
        assertNull(backgroundBrightness(IntArray(0), 1.75f))
    }

    @Test fun alphaIsIgnored() {
        assertAll(1f, measure(IntArray(4) { 0x00FFFFFF }).wcag, 1e-4f)
    }

    @Test fun eightyPercentBlackTwentyPercentWhiteSplitsTheStatistics() {
        val result = measure(IntArray(100) { if (it < 80) black else white })
        for (stats in listOf(result.wcag, result.oklab)) {
            assertEquals(0.2f, stats.mean, 1e-4f)
            assertEquals(0f, stats.median, 1e-6f)
            assertEquals(1f, stats.p90, 1e-4f)
        }
    }

    @Test fun p90IsNearestRank() {
        val result = measure(IntArray(100) { if (it < 91) black else white })
        assertEquals(0f, result.wcag.p90, 1e-6f)
        assertEquals(1f, measure(IntArray(100) { if (it < 89) black else white }).wcag.p90, 1e-4f)
    }

    @Test fun midGreyMatchesKnownValues() {
        val result = measure(IntArray(10) { 0xFF808080.toInt() })
        // sRGB 0x80 is about 0.2159 linear; its OKLab lightness is the cube root of that.
        assertEquals(0.2159f, result.wcag.mean, 2e-3f)
        assertEquals(0.5998f, result.oklab.mean, 3e-3f)
    }

    @Test fun saturationLeavesGreyAloneAndChangesAColor() {
        val grey = intArrayOf(0xFF808080.toInt())
        assertEquals(measure(grey, 1f).wcag.mean, measure(grey, 1.75f).wcag.mean, 1e-4f)
        assertEquals(measure(grey, 1f).oklab.mean, measure(grey, 1.75f).oklab.mean, 1e-4f)
        val muted = intArrayOf(0xFF804040.toInt())
        assertTrue(measure(muted, 1.75f).wcag.mean != measure(muted, 1f).wcag.mean)
        assertTrue(measure(muted, 1.75f).oklab.mean != measure(muted, 1f).oklab.mean)
    }
}
