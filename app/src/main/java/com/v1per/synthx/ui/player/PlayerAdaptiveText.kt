package com.v1per.synthx.ui.player

import com.v1per.synthx.playback.BackgroundBrightness
import com.v1per.synthx.playback.BrightnessStats

/** Which brightness measure the adaptive title color reads. */
internal enum class AdaptiveTextMetric { WcagLuminance, }

/** Which statistic of the metric the adaptive title color reads. */
internal enum class AdaptiveTextStatistic { Median, }

internal fun BrightnessStats.of(statistic: AdaptiveTextStatistic): Float = when (statistic) {
    AdaptiveTextStatistic.Median -> median
}

/** The value picked by [metric] and [statistic]. */
internal fun BackgroundBrightness.select(metric: AdaptiveTextMetric, statistic: AdaptiveTextStatistic): Float =
    when (metric) {
        AdaptiveTextMetric.WcagLuminance -> wcag.of(statistic)
    }

/** Dark text only when the feature is on, a brightness was measured, and it is above [threshold]. */
internal fun useDarkPlayerTitle(
    enabled: Boolean,
    brightness: BackgroundBrightness?,
    metric: AdaptiveTextMetric,
    statistic: AdaptiveTextStatistic,
    threshold: Float,
): Boolean = enabled && brightness != null && brightness.select(metric, statistic) > threshold
