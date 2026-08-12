package com.v1per.synthx.ui.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

object Motion {
    const val DurationFast = 150
    const val DurationNormal = 300
    const val DurationSlow = 500

    val EasingStandard = FastOutSlowInEasing
    val EasingDecelerate = LinearOutSlowInEasing
    val EasingAccelerate = FastOutLinearInEasing

    /** Shared damped-spring feel for settle/breathe animations — see PlayerAlbumArt and PlayerSheet. */
    val SpringStandard: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
