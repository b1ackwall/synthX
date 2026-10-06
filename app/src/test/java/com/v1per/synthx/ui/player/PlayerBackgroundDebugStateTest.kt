package com.v1per.synthx.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackgroundDebugStateTest {

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
}
