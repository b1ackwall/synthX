package com.v1per.synthx.ui.theme

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

object Motion {
    /** For fast, scroll-driven chrome transitions where snappy responsiveness matters more than a graceful fade. */
    const val DurationInstant = 100
    const val DurationFast = 150
    const val DurationNormal = 300

    /** Passes a song title or artist marquee runs per text change; it then rests at its start. */
    const val MarqueeIterations = 1

    val EasingStandard = FastOutSlowInEasing
    val EasingDecelerate = LinearOutSlowInEasing
    val EasingAccelerate = FastOutLinearInEasing

    /** Shared damped-spring feel for settle/breathe animations — see PlayerAlbumArt and PlayerSheetSkeleton. */
    val SpringStandard: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
