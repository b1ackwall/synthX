package com.v1per.synthx.ui.player

import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.playback.BrightnessStats
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerBackgroundDebugStateTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

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

    private val brightness = BackgroundBrightness(
        wcag = BrightnessStats(mean = 0.1f, median = 0.2f, p90 = 0.3f),
        oklab = BrightnessStats(mean = 0.4f, median = 0.5f, p90 = 0.6f),
    )

    @Test fun selectionPicksTheChosenMetricAndStatistic() {
        assertEquals(0.1f, brightness.select(AdaptiveTextMetric.WcagLuminance, AdaptiveTextStatistic.Mean))
        assertEquals(0.2f, brightness.select(AdaptiveTextMetric.WcagLuminance, AdaptiveTextStatistic.Median))
        assertEquals(0.3f, brightness.select(AdaptiveTextMetric.WcagLuminance, AdaptiveTextStatistic.P90))
        assertEquals(0.4f, brightness.select(AdaptiveTextMetric.OklabLightness, AdaptiveTextStatistic.Mean))
        assertEquals(0.5f, brightness.select(AdaptiveTextMetric.OklabLightness, AdaptiveTextStatistic.Median))
        assertEquals(0.6f, brightness.select(AdaptiveTextMetric.OklabLightness, AdaptiveTextStatistic.P90))
    }

    @Test fun darkTitleNeedsEnabledAValueAndSelectedValueAboveThreshold() {
        val wcag = AdaptiveTextMetric.WcagLuminance
        val oklab = AdaptiveTextMetric.OklabLightness
        val p90 = AdaptiveTextStatistic.P90
        assertEquals(true, useDarkPlayerTitle(true, brightness, wcag, p90, 0.25f))
        assertEquals(false, useDarkPlayerTitle(true, brightness, wcag, p90, 0.3f))
        assertEquals(false, useDarkPlayerTitle(true, brightness, wcag, AdaptiveTextStatistic.Mean, 0.25f))
        assertEquals(true, useDarkPlayerTitle(true, brightness, oklab, AdaptiveTextStatistic.Mean, 0.25f))
        assertEquals(false, useDarkPlayerTitle(false, brightness, oklab, p90, 0.25f))
        assertEquals(false, useDarkPlayerTitle(true, null, wcag, p90, 0.25f))
    }
}
