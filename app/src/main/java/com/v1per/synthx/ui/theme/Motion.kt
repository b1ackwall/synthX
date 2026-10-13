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

    val EasingStandard = FastOutSlowInEasing
    val EasingDecelerate = LinearOutSlowInEasing
    val EasingAccelerate = FastOutLinearInEasing

    /** Shared damped-spring feel for settle/breathe animations — see PlayerAlbumArt and PlayerSheetSkeleton. */
    val SpringStandard: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * Snappier than [SpringStandard] — used for target-based dismissal (e.g.
     * RefinedBottomSheet's drag-to-close). Springs always converge to the target regardless of
     * starting velocity, so a fast flick still reads as fast (the spring inherits that initial
     * velocity) while a slow release settles smoothly instead of stalling like a pure decay
     * animation with little velocity to coast on.
     */
    val SpringSnappy: SpringSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
