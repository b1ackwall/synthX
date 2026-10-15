package com.v1per.synthx.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueueMaskQuantizationTest {

    @Test fun strengthEndsMapToTheEndStepsAndBackExactly() {
        assertEquals(0, queueMaskStrengthStep(0f))
        assertEquals(QueueMaskStrengthSteps, queueMaskStrengthStep(1f))
        assertEquals(0f, queueMaskStrengthStep(0f) / QueueMaskStrengthSteps.toFloat())
        assertEquals(1f, queueMaskStrengthStep(1f) / QueueMaskStrengthSteps.toFloat())
    }

    @Test fun strengthOutsideTheRangeIsClamped() {
        assertEquals(0, queueMaskStrengthStep(-0.5f))
        assertEquals(QueueMaskStrengthSteps, queueMaskStrengthStep(1.5f))
    }

    @Test fun strengthStepsAreMonotonic() {
        var previous = queueMaskStrengthStep(0f)
        for (i in 1..10_000) {
            val step = queueMaskStrengthStep(i / 10_000f)
            assertTrue(step >= previous, "step $step after $previous at ${i / 10_000f}")
            previous = step
        }
        assertEquals(QueueMaskStrengthSteps, previous)
    }

    @Test fun strengthRoundsToTheNearestStep() {
        // 0.5 x 255 = 127.5 rounds up; 0.25 x 255 = 63.75; 0.1 x 255 = 25.5 rounds up.
        assertEquals(128, queueMaskStrengthStep(0.5f))
        assertEquals(64, queueMaskStrengthStep(0.25f))
        assertEquals(26, queueMaskStrengthStep(0.1f))
        assertEquals(0, queueMaskStrengthStep(0.4f / QueueMaskStrengthSteps))
        assertEquals(1, queueMaskStrengthStep(0.6f / QueueMaskStrengthSteps))
    }

    @Test fun strengthWithinHalfAStepKeepsItsStep() {
        for (step in listOf(1, 64, 128, 200, 254)) {
            val center = step / QueueMaskStrengthSteps.toFloat()
            val nudge = 0.4f / QueueMaskStrengthSteps
            assertEquals(step, queueMaskStrengthStep(center - nudge))
            assertEquals(step, queueMaskStrengthStep(center))
            assertEquals(step, queueMaskStrengthStep(center + nudge))
        }
    }

    @Test fun fadeEndRoundsToWholePixels() {
        assertEquals(1264, queueMaskFadeEndPx(1264f))
        assertEquals(1264, queueMaskFadeEndPx(1264.4f))
        assertEquals(1265, queueMaskFadeEndPx(1264.5f))
        assertEquals(1265, queueMaskFadeEndPx(1264.6f))
        assertEquals(-3, queueMaskFadeEndPx(-3.4f))
        assertEquals(-4, queueMaskFadeEndPx(-3.6f))
    }

    @Test fun fadeEndWithinHalfAPixelKeepsItsPixel() {
        for (px in listOf(0, 1264, 1939, 2389)) {
            assertEquals(px, queueMaskFadeEndPx(px - 0.4f))
            assertEquals(px, queueMaskFadeEndPx(px + 0.4f))
        }
    }
}
