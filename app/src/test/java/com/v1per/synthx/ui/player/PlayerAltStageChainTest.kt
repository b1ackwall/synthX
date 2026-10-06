package com.v1per.synthx.ui.player

import kotlin.test.Test
import kotlin.test.assertEquals

// RenderEffect does not load in the JVM stub, so only the order-selection logic is tested here.
class PlayerAltStageChainTest {

    private val blurPx = PlayerAltStageStyle.BlurDp * 3f

    @Test fun twistOnBlurOnIsBlurThenTwistThenSaturation() {
        assertEquals(
            listOf(AltStageStep.Blur, AltStageStep.Twist, AltStageStep.Saturation),
            altStageChain(blurPx, twistEnabled = true, twistSupported = true),
        )
    }

    @Test fun twistOnBlurZeroIsTwistThenSaturation() {
        assertEquals(
            listOf(AltStageStep.Twist, AltStageStep.Saturation),
            altStageChain(0f, twistEnabled = true, twistSupported = true),
        )
    }

    @Test fun twistOffBlurOnIsBlurThenSaturation() {
        assertEquals(
            listOf(AltStageStep.Blur, AltStageStep.Saturation),
            altStageChain(blurPx, twistEnabled = false, twistSupported = true),
        )
    }

    @Test fun twistOffBlurZeroIsSaturationOnly() {
        assertEquals(
            listOf(AltStageStep.Saturation),
            altStageChain(0f, twistEnabled = false, twistSupported = true),
        )
    }

    @Test fun belowApi33TheTwistIsSkippedWhateverTheSwitch() {
        assertEquals(
            listOf(AltStageStep.Blur, AltStageStep.Saturation),
            altStageChain(blurPx, twistEnabled = true, twistSupported = false),
        )
        assertEquals(
            listOf(AltStageStep.Saturation),
            altStageChain(0f, twistEnabled = true, twistSupported = false),
        )
    }

    @Test fun theLockedProductionChainIsBlurTwistSaturation() {
        assertEquals(
            listOf(AltStageStep.Blur, AltStageStep.Twist, AltStageStep.Saturation),
            altStageChain(PlayerAltStageStyle.BlurDp * 3f, PlayerAltStageStyle.TwistEnabled, twistSupported = true),
        )
        assertEquals(AltStageBlurEdgeMode.Clamp, PlayerAltStageStyle.BlurEdgeMode)
    }
}
