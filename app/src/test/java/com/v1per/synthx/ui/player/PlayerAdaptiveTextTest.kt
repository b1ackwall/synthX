package com.v1per.synthx.ui.player

import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.playback.BrightnessStats
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAdaptiveTextTest {
    private fun withMedian(median: Float) = BackgroundBrightness(
        wcag = BrightnessStats(mean = 0.9f, median = median, p90 = 0.9f),
        oklab = BrightnessStats(mean = 0.9f, median = 0.9f, p90 = 0.9f),
    )

    private fun dark(brightness: BackgroundBrightness?) = useDarkPlayerTitle(
        enabled = PlayerAltStageStyle.AdaptiveTextEnabled,
        brightness = brightness,
        metric = PlayerAltStageStyle.LockedAdaptiveTextMetric,
        statistic = PlayerAltStageStyle.LockedAdaptiveTextStatistic,
        threshold = PlayerAltStageStyle.AdaptiveTextThreshold,
    )

    @Test fun lockedConstantsEqualTheTunedValues() {
        assertEquals(true, PlayerAltStageStyle.AdaptiveTextEnabled)
        assertEquals(0.32f, PlayerAltStageStyle.AdaptiveTextThreshold)
        assertEquals(AdaptiveTextMetric.WcagLuminance, PlayerAltStageStyle.LockedAdaptiveTextMetric)
        assertEquals(AdaptiveTextStatistic.Median, PlayerAltStageStyle.LockedAdaptiveTextStatistic)
    }

    @Test fun equalToTheThresholdStaysLight() {
        assertEquals(false, dark(withMedian(0.32f)))
    }

    @Test fun justAboveTheThresholdGoesDark() {
        assertEquals(true, dark(withMedian(Math.nextUp(0.32f))))
        assertEquals(true, dark(withMedian(0.5f)))
    }

    @Test fun belowTheThresholdStaysLight() {
        assertEquals(false, dark(withMedian(0.1f)))
    }

    @Test fun noValueStaysLight() {
        assertEquals(false, dark(null))
    }

    @Test fun onlyTheLockedMetricAndStatisticDecide() {
        // Mean, P90 and the OKLab values are all high; the WCAG median is low.
        assertEquals(false, dark(withMedian(0.1f)))
    }

    @Test fun darkAndLightColorsAreTheExistingTokens() {
        assertEquals(com.v1per.synthx.ui.theme.Background, playerTitleColor(true))
        assertEquals(com.v1per.synthx.ui.theme.Background, playerArtistColor(true))
        assertEquals(com.v1per.synthx.ui.theme.TextPrimary, playerTitleColor(false))
        assertEquals(com.v1per.synthx.ui.theme.TextSecondary, playerArtistColor(false))
    }
}
