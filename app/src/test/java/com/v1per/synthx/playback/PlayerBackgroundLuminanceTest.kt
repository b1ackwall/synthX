package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerBackgroundLuminanceTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun measure(pixels: IntArray, saturation: Float = 1f) =
        assertNotNull(backgroundBrightness(pixels, saturation))

    private fun assertAll(expected: Float, stats: Float, tolerance: Float) {
        assertEquals(expected, stats, tolerance)
    }

    @Test fun allWhiteGivesOne() {
        val result = measure(IntArray(16) { white }, saturation = 1.75f)
        assertAll(1f, result, 1e-4f)
    }

    @Test fun allBlackGivesZero() {
        val result = measure(IntArray(16) { black }, saturation = 1.75f)
        assertAll(0f, result, 1e-6f)
    }

    @Test fun noPixelsYieldsNoValue() {
        assertNull(backgroundBrightness(IntArray(0), 1.75f))
    }

    @Test fun alphaIsIgnored() {
        assertAll(1f, measure(IntArray(4) { 0x00FFFFFF }), 1e-4f)
    }

    @Test fun eightyPercentBlackTwentyPercentWhiteSplitsTheStatistics() {
        val result = measure(IntArray(100) { if (it < 80) black else white })
        assertEquals(0f, result, 1e-6f)
    }

    @Test fun medianIsNearestRank() {
        val result = measure(IntArray(100) { if (it < 50) black else white })
        assertEquals(0f, result, 1e-6f)
        assertEquals(1f, measure(IntArray(100) { if (it < 49) black else white }), 1e-4f)
    }

    @Test fun midGreyMatchesKnownValues() {
        val result = measure(IntArray(10) { 0xFF808080.toInt() })
        // sRGB 0x80 is about 0.2159 linear.
        assertEquals(0.2159f, result, 2e-3f)
    }

    @Test fun saturationLeavesGreyAloneAndChangesAColor() {
        val grey = intArrayOf(0xFF808080.toInt())
        assertEquals(measure(grey, 1f), measure(grey, 1.75f), 1e-4f)
        val muted = intArrayOf(0xFF804040.toInt())
        assertTrue(measure(muted, 1.75f) != measure(muted, 1f))
    }
}
