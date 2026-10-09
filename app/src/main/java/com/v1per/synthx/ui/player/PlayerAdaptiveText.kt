package com.v1per.synthx.ui.player

// DEAD CODE: only the retired playerTitleColor/playerArtistColor below used these. Delete in a cleanup pass. Ask  before deleting.
// import androidx.compose.ui.graphics.Color
import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.playback.BrightnessStats
// DEAD CODE: only the retired playerTitleColor/playerArtistColor below used these. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.ui.theme.Background
// import com.v1per.synthx.ui.theme.TextPrimary
// import com.v1per.synthx.ui.theme.TextSecondary

/** Which brightness measure the adaptive title color reads. */
internal enum class AdaptiveTextMetric { WcagLuminance, OklabLightness }

/** Which statistic of the metric the adaptive title color reads. */
internal enum class AdaptiveTextStatistic { Mean, Median, P90 }

internal fun BrightnessStats.of(statistic: AdaptiveTextStatistic): Float = when (statistic) {
    AdaptiveTextStatistic.Mean -> mean
    AdaptiveTextStatistic.Median -> median
    AdaptiveTextStatistic.P90 -> p90
}

/** The value picked by [metric] and [statistic]. */
internal fun BackgroundBrightness.select(metric: AdaptiveTextMetric, statistic: AdaptiveTextStatistic): Float =
    when (metric) {
        AdaptiveTextMetric.WcagLuminance -> wcag.of(statistic)
        AdaptiveTextMetric.OklabLightness -> oklab.of(statistic)
    }

/** Dark text only when the feature is on, a brightness was measured, and it is above [threshold]. */
internal fun useDarkPlayerTitle(
    enabled: Boolean,
    brightness: BackgroundBrightness?,
    metric: AdaptiveTextMetric,
    statistic: AdaptiveTextStatistic,
    threshold: Float,
): Boolean = enabled && brightness != null && brightness.select(metric, statistic) > threshold

// DEAD CODE: the title and artist read PlayerContentColors from LocalPlayerContentColors instead. Delete in a cleanup pass. Ask  before deleting.
// /** The expanded title color: the darkest token on a bright background, else the usual one. */
// internal fun playerTitleColor(dark: Boolean): Color = if (dark) Background else TextPrimary
//
// /** The expanded artist color: the darkest token on a bright background, else the usual one. */
// internal fun playerArtistColor(dark: Boolean): Color = if (dark) Background else TextSecondary
