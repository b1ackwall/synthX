package com.v1per.synthx.ui.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

object Motion {
    const val DurationFast = 150
    const val DurationNormal = 300
    const val DurationSlow = 500

    val EasingStandard = FastOutSlowInEasing
    val EasingDecelerate = LinearOutSlowInEasing
    val EasingAccelerate = FastOutLinearInEasing
}
