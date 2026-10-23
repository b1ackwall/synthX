package com.v1per.synthx.ui.player

import com.v1per.synthx.playback.BackgroundBrightness

/** Dark text only when the feature is on, a brightness was measured, and its median is above [threshold]. */
internal fun useDarkPlayerTitle(enabled: Boolean, brightness: BackgroundBrightness?, threshold: Float): Boolean =
    enabled && brightness != null && brightness.wcag.median > threshold
