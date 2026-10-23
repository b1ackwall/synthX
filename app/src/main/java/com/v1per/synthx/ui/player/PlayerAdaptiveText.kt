package com.v1per.synthx.ui.player

/** Dark text only when the feature is on, a brightness was measured, and its median is above [threshold]. */
internal fun useDarkPlayerTitle(enabled: Boolean, brightness: Float?, threshold: Float): Boolean =
    enabled && brightness != null && brightness > threshold
