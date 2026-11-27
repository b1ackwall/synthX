package com.v1per.synthx.ui.theme

import androidx.compose.ui.unit.dp

/** Sizes for the onboarding welcome screen (ui/screens/onboarding/OnboardingChoiceStep.kt). */
object OnboardingSizes {
    /** The widest the wordmark, subtitle and hairline column gets; a wider window centres it. */
    val ContentMaxWidth = 480.dp

    /** The widest the wordmark gets. */
    val WordmarkMaxWidth = 240.dp

    /** The wordmark's width as a share of the screen width, up to [WordmarkMaxWidth]. */
    const val WordmarkWidthFraction = 0.4f

    /** The glow's radius as a multiple of the wordmark's width. */
    const val GlowRadiusPerWordmarkWidth = 1f
}
