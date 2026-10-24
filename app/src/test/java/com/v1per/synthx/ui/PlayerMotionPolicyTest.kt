package com.v1per.synthx.ui

import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.dp
import com.v1per.synthx.ui.player.shouldPauseAmbientMotion
import com.v1per.synthx.ui.player.shouldReduceAmbientMotion
import com.v1per.synthx.ui.player.queueCompositingStrategy
import com.v1per.synthx.ui.player.quantizePlayerGlowSize
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMotionPolicyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun eitherSystemPolicyForcesReducedMotion() {
        assertFalse(shouldReduceAmbientMotion(powerSaveMode = false, animatorDurationScale = 1f))
        assertTrue(shouldReduceAmbientMotion(powerSaveMode = true, animatorDurationScale = 1f))
        assertTrue(shouldReduceAmbientMotion(powerSaveMode = false, animatorDurationScale = 0f))
        assertTrue(shouldPauseAmbientMotion(instancePaused = false, systemReducedMotionActive = true))
        assertTrue(shouldPauseAmbientMotion(instancePaused = true, systemReducedMotionActive = false))
        assertFalse(shouldPauseAmbientMotion(instancePaused = false, systemReducedMotionActive = false))
    }

    @Test fun queueUsesOffscreenCompositingOnlyWhileVisible() {
        assertTrue(queueCompositingStrategy(0f) == CompositingStrategy.Auto)
        assertTrue(queueCompositingStrategy(0.01f) == CompositingStrategy.Offscreen)
    }

    @Test fun glowQuantizationPreservesMiniEndpointAndAdvancesInFourDpSteps() {
        // 44dp mini art + 2dp spread on each side.
        assertEquals(48.dp, quantizePlayerGlowSize(48.dp))
        assertEquals(48.dp, quantizePlayerGlowSize(49.9.dp))
        assertEquals(52.dp, quantizePlayerGlowSize(50.1.dp))
        assertEquals(52.dp, quantizePlayerGlowSize(53.9.dp))
        assertEquals(56.dp, quantizePlayerGlowSize(54.1.dp))
    }
}
