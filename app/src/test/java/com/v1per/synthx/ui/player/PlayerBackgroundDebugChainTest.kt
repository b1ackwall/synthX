package com.v1per.synthx.ui.player

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerBackgroundDebugChainTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun defaultsAreTheLockedProductionChain() {
        val state = PlayerBackgroundDebugState()
        assertEquals(PlayerAltStageStyle.BlurDp, state.blurRadiusDp)
        assertEquals(PlayerAltStageStyle.TwistEnabled, state.twistEnabled)
        assertEquals(PlayerAltStageStyle.BlurEdgeMode, state.blurEdgeMode)
        assertTrue(state.twistEnabled)
        assertEquals(AltStageBlurEdgeMode.Clamp, state.blurEdgeMode)
    }

    @Test fun edgeModesAreClampMirrorDecalRepeat() {
        assertEquals(
            listOf("Clamp", "Mirror", "Decal", "Repeat"),
            AltStageBlurEdgeMode.entries.map { it.name },
        )
    }

    @Test fun settersChangeOnlyTheirOwnValue() {
        PlayerBackgroundDebugStateHolder.reset()
        PlayerBackgroundDebugStateHolder.setTwistEnabled(false)
        PlayerBackgroundDebugStateHolder.setBlurEdgeMode(AltStageBlurEdgeMode.Decal)
        PlayerBackgroundDebugStateHolder.setBlurRadiusDp(77f)
        assertEquals(PlayerBackgroundDebugState(77f, false, AltStageBlurEdgeMode.Decal), PlayerBackgroundDebugStateHolder.state.value)
        PlayerBackgroundDebugStateHolder.reset()
    }

    @Test fun resetRestoresAllThreeValues() {
        PlayerBackgroundDebugStateHolder.setBlurRadiusDp(5f)
        PlayerBackgroundDebugStateHolder.setTwistEnabled(false)
        PlayerBackgroundDebugStateHolder.setBlurEdgeMode(AltStageBlurEdgeMode.Mirror)
        PlayerBackgroundDebugStateHolder.reset()
        val state = PlayerBackgroundDebugStateHolder.state.value
        assertEquals(PlayerAltStageStyle.BlurDp, state.blurRadiusDp)
        assertEquals(PlayerAltStageStyle.TwistEnabled, state.twistEnabled)
        assertEquals(PlayerAltStageStyle.BlurEdgeMode, state.blurEdgeMode)
    }

    @Test fun releaseBuildsIgnoreTheDebugTwistAndEdgeMode() {
        val tuned = PlayerBackgroundDebugState(10f, twistEnabled = false, blurEdgeMode = AltStageBlurEdgeMode.Repeat)
        assertEquals(PlayerAltStageStyle.TwistEnabled, effectivePlayerBackgroundTwistEnabled(debug = false, state = tuned))
        assertEquals(PlayerAltStageStyle.BlurEdgeMode, effectivePlayerBackgroundBlurEdgeMode(debug = false, state = tuned))
        assertEquals(false, effectivePlayerBackgroundTwistEnabled(debug = true, state = tuned))
        assertEquals(AltStageBlurEdgeMode.Repeat, effectivePlayerBackgroundBlurEdgeMode(debug = true, state = tuned))
    }
}
