package com.v1per.synthx.ui.player

import androidx.compose.ui.graphics.BlendMode
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGrainTest {
    private val locked = generateGrainPixels(PlayerAltStageStyle.GrainSeed, PlayerAltStageStyle.GrainContrast)

    @Test
    fun lockedConstantsStayInTheTunedRanges() {
        assertTrue(PlayerAltStageStyle.GrainStrength > 0f && PlayerAltStageStyle.GrainStrength <= 0.40f)
        assertTrue(PlayerAltStageStyle.GrainSizeDp in 0.5f..4.0f)
        assertTrue(PlayerAltStageStyle.GrainContrast in 0f..1f)
        assertEquals(BlendMode.Softlight, PlayerAltStageStyle.GrainBlendMode)
    }

    @Test
    fun lockedValuesAreTheOwnersTunedLook() {
        assertEquals(0.2f, PlayerAltStageStyle.GrainStrength, 0f)
        assertEquals(0.5f, PlayerAltStageStyle.GrainSizeDp, 0f)
        assertEquals(0.4f, PlayerAltStageStyle.GrainContrast, 0f)
        assertEquals(1, PlayerAltStageStyle.GrainSeed)
    }

    @Test
    fun tileIsDeterministicForTheLockedSeedAndDiffersForAnother() {
        assertTrue(locked.contentEquals(generateGrainPixels(PlayerAltStageStyle.GrainSeed, 0.4f)))
        assertFalse(locked.contentEquals(generateGrainPixels(PlayerAltStageStyle.GrainSeed + 1, 0.4f)))
    }

    @Test
    fun everyPixelIsOpaqueGreyInsideTheContrastBand() {
        val half = PlayerAltStageStyle.GrainContrast / 2f
        val low = ((0.5f - half) * 255f).toInt() - 1
        val high = ((0.5f + half) * 255f).toInt() + 1
        var sum = 0L
        for (pixel in locked) {
            val r = (pixel shr 16) and 0xFF
            assertEquals(0xFF, (pixel ushr 24) and 0xFF)
            assertEquals(r, (pixel shr 8) and 0xFF)
            assertEquals(r, pixel and 0xFF)
            assertTrue("grey $r outside $low..$high", r in low..high)
            sum += r
        }
        val mean = sum.toDouble() / locked.size
        assertTrue("mean $mean is not near mid grey", abs(mean - 127.5) < 2.0)
    }

    @Test
    fun tileHasTheFullSideAndMoreThanOneValue() {
        assertEquals(GrainTileSidePx * GrainTileSidePx, locked.size)
        assertTrue(locked.toSet().size > 10)
    }

    @Test
    fun speckleSizeInPixelsIsTheLockedDpTimesDensity() {
        for (density in listOf(1.0f, 2.75f, 3.5f)) {
            assertEquals(PlayerAltStageStyle.GrainSizeDp * density, grainScale(density), 0f)
        }
        assertEquals(1.375f, grainScale(2.75f), 0f)
    }

    @Test
    fun lockedTilePixelsMatchTheGoldenFingerprint() {
        val bytes = ByteBuffer.allocate(locked.size * 4).also { buffer -> locked.forEach { buffer.putInt(it) } }.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("392610d1b76fcd2a3e67112a09e3af3f84eb49c8ba766ecb4171a3bae3ee4aa5", digest)
    }
}
