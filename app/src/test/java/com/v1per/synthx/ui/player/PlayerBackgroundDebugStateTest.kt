package com.v1per.synthx.ui.player

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackgroundDebugStateTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun defaultIsTheLockedProductionBlur() {
        assertEquals(PlayerAltStageStyle.BlurDp, PlayerBackgroundDebugState().blurRadiusDp)
        assertEquals(120f, PlayerBackgroundDebugState().blurRadiusDp)
    }

    @Test fun valuesAreClampedToZeroThroughThreeHundred() {
        assertEquals(0f, clampPlayerBackgroundBlurDp(-5f))
        assertEquals(0f, clampPlayerBackgroundBlurDp(0f))
        assertEquals(42.5f, clampPlayerBackgroundBlurDp(42.5f))
        assertEquals(300f, clampPlayerBackgroundBlurDp(300f))
        assertEquals(300f, clampPlayerBackgroundBlurDp(900f))
        assertEquals(PlayerAltStageStyle.BlurDp, clampPlayerBackgroundBlurDp(Float.NaN))
    }

    @Test fun holderClampsWhatItStoresAndResetRestoresTheDefault() {
        PlayerBackgroundDebugStateHolder.setBlurRadiusDp(1000f)
        assertEquals(300f, PlayerBackgroundDebugStateHolder.state.value.blurRadiusDp)
        PlayerBackgroundDebugStateHolder.setBlurRadiusDp(-1f)
        assertEquals(0f, PlayerBackgroundDebugStateHolder.state.value.blurRadiusDp)
        PlayerBackgroundDebugStateHolder.reset()
        assertEquals(PlayerAltStageStyle.BlurDp, PlayerBackgroundDebugStateHolder.state.value.blurRadiusDp)
    }

    @Test fun releaseBuildsAlwaysUseTheLockedBlur() {
        val tuned = PlayerBackgroundDebugState(blurRadiusDp = 10f)
        assertEquals(PlayerAltStageStyle.BlurDp, effectivePlayerBackgroundBlurDp(debug = false, state = tuned))
        assertEquals(10f, effectivePlayerBackgroundBlurDp(debug = true, state = tuned))
    }

    private val brightness = 0.2f

    @Test fun selectionPicksTheChosenMetricAndStatistic() {
        assertEquals(0.2f, brightness)
    }

    @Test fun darkTitleNeedsEnabledAValueAndSelectedValueAboveThreshold() {
        assertEquals(true, useDarkPlayerTitle(true, brightness, 0.15f))
        assertEquals(false, useDarkPlayerTitle(true, brightness, 0.2f))
        assertEquals(false, useDarkPlayerTitle(false, brightness, 0.15f))
        assertEquals(false, useDarkPlayerTitle(true, null, 0.15f))
    }
}
