package com.v1per.synthx.ui.player

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGlowImageTest {
    private val boxCells = 33
    private val sigmaCells = 8f
    private val padCells = glowPadCells(sigmaCells)
    private val side = boxCells + 2 * padCells
    private val mini = generateGlowAlpha(boxCells, padCells, sigmaCells, peakAlpha = 0.8f)

    private fun ByteArray.at(x: Int, y: Int, rowLength: Int = side): Int = this[y * rowLength + x].toInt() and 0xFF

    @Test
    fun imageIsASquareOfTheBoxPlusPaddingOnBothSides() {
        assertEquals(side * side, mini.size)
        assertEquals(33 + 2 * 32, side)
    }

    @Test
    fun paddingIsFourSigmasRoundedUp() {
        assertEquals(32, glowPadCells(8f))
        assertEquals(33, glowPadCells(8.1f))
    }

    @Test
    fun imageIsSymmetricAcrossBothAxesAndTheDiagonal() {
        for (y in 0 until side) {
            for (x in 0 until side) {
                val v = mini.at(x, y)
                assertEquals("mirror x at $x,$y", v, mini.at(side - 1 - x, y))
                assertEquals("mirror y at $x,$y", v, mini.at(x, side - 1 - y))
                assertEquals("transpose at $x,$y", v, mini.at(y, x))
            }
        }
    }

    @Test
    fun alphaFallsOffMonotonicallyFromTheCentreToTheEdge() {
        val mid = side / 2
        for (x in 1..mid) {
            assertTrue("row not monotonic at $x", mini.at(x - 1, mid) <= mini.at(x, mid))
            assertTrue("column not monotonic at $x", mini.at(mid, x - 1) <= mini.at(mid, x))
            assertTrue("diagonal not monotonic at $x", mini.at(x - 1, x - 1) <= mini.at(x, x))
        }
        assertTrue(mini.at(mid, mid) > mini.at(mid, mid - 10))
    }

    @Test
    fun outermostRingIsFullyTransparent() {
        for (i in 0 until side) {
            assertEquals(0, mini.at(i, 0))
            assertEquals(0, mini.at(i, side - 1))
            assertEquals(0, mini.at(0, i))
            assertEquals(0, mini.at(side - 1, i))
        }
    }

    @Test
    fun centreOfABoxMuchWiderThanTheBlurReachesThePeakAlpha() {
        val wide = 200
        val pad = glowPadCells(sigmaCells)
        val image = generateGlowAlpha(wide, pad, sigmaCells, peakAlpha = 0.8f)
        val wideSide = wide + 2 * pad
        assertEquals(204, image.at(wideSide / 2, wideSide / 2, wideSide))
    }

    @Test
    fun centreOfABoxSmallerThanTheBlurStaysBelowThePeak() {
        val centre = mini.at(side / 2, side / 2)
        assertTrue("centre $centre", centre in 150..203)
    }

    @Test
    fun smallBoxCentreMatchesTheAnalyticValue() {
        // 0.8 * (2 * Phi(16.5 / 8) - 1)^2 * 255 = 188.3
        assertEquals(188.3, mini.at(side / 2, side / 2).toDouble(), 1.0)
        // 8.5 cells in from the left edge, on the middle row: 0.8 * (Phi(1.0625) - Phi(-3.0625)) * 0.9609 * 255.
        assertEquals(167.5, mini.at(padCells + 8, side / 2).toDouble(), 1.5)
    }

    @Test
    fun alphaAtTheMiddleOfAnEdgeIsAboutHalfThePeak() {
        val wide = 200
        val pad = glowPadCells(sigmaCells)
        val image = generateGlowAlpha(wide, pad, sigmaCells, peakAlpha = 0.8f)
        val wideSide = wide + 2 * pad
        val atEdge = image.at(pad, wideSide / 2, wideSide)
        assertTrue("edge alpha $atEdge", atEdge in 100..112)
    }

    @Test
    fun peakAlphaScalesEveryPixel() {
        val half = generateGlowAlpha(boxCells, padCells, sigmaCells, peakAlpha = 0.4f)
        val mid = side / 2
        assertEquals(mini.at(mid, mid) / 2.0, half.at(mid, mid).toDouble(), 1.0)
    }

    @Test
    fun sameInputsGiveTheSameBytesAndDifferentInputsDoNot() {
        assertTrue(mini.contentEquals(generateGlowAlpha(boxCells, padCells, sigmaCells, 0.8f)))
        assertFalse(mini.contentEquals(generateGlowAlpha(boxCells, padCells, sigmaCells + 1f, 0.8f)))
    }

    @Test
    fun sigmaFollowsTheSkiaRadiusConversion() {
        assertEquals(32.25425f, glowSigmaPx(55f), 0.001f)
        assertEquals(0f, glowSigmaPx(0f), 0f)
    }

    @Test
    fun cellsPerBoxRoundsTheBoxPixelsToTheCellSizeAndNeverReachesZero() {
        assertEquals(33, glowBoxCells(132, GlowCellPx))
        assertEquals(229, glowBoxCells(913, GlowCellPx))
        assertEquals(1, glowBoxCells(0, GlowCellPx))
    }

    @Test
    fun miniImagePixelsMatchTheGoldenFingerprint() {
        val digest = MessageDigest.getInstance("SHA-256").digest(mini).joinToString("") { "%02x".format(it) }
        assertEquals("a6cee6d4ae7657d281bef6a582d65862e4360e01a9a26727740a7f0bf0a1da4c", digest)
    }
}
