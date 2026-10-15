package com.v1per.synthx.ui.player

import kotlin.math.PI

/**
 * One square copy of the art. [sizeFraction] and [orbitRadius] are fractions of the layer width;
 * speeds are radians per second. Orbit values apply only to [PlayerAltStageStyle.OrbitingCopies].
 */
internal data class AltStageArtCopy(
    val sizeFraction: Float,
    val spinSpeed: Float,
    val clockwise: Boolean,
    val orbitRadius: Float = 0f,
    val orbitSpeed: Float = 0f,
)

/** Locked values for the expanded player's art background, ported from the AltStage prototype. */
internal object PlayerAltStageStyle {
    /** Back, Middle, Inner orbit, Outer orbit. */
    val Copies = listOf(
        AltStageArtCopy(sizeFraction = 1.25f, spinSpeed = 0.05f, clockwise = true),
        AltStageArtCopy(sizeFraction = 0.80f, spinSpeed = 0.07f, clockwise = false),
        AltStageArtCopy(sizeFraction = 0.50f, spinSpeed = 0.10f, clockwise = true, orbitRadius = 0.25f, orbitSpeed = 0.06f),
        AltStageArtCopy(sizeFraction = 0.25f, spinSpeed = 0.14f, clockwise = false, orbitRadius = 0.40f, orbitSpeed = -0.09f),
    )

    /** Copy indices that circle the layer center; orbit slots follow this order. */
    val OrbitingCopies = listOf(2, 3)

    /** Starting angle of each orbit slot, in radians. */
    val OrbitStartAngles = listOf(0f, PI.toFloat())

    /** Base layer overscan: the art is stretched to this multiple of the layer's width and height. */
    const val BaseScale = 1.1f

    /** Base layer drift rotation, radians per second. */
    const val BaseDriftSpeed = 0.03f

    /** Twist angle at its center, in radians. */
    const val TwistAngle = 1.5f

    /** Twist radius as a fraction of the layer width. */
    const val TwistRadius = 0.6f

    /** Twist center as fractions of the layer width and height. */
    const val TwistOffsetX = 0.4f
    const val TwistOffsetY = 0.4f

    const val BlurDp = 120f

    /** The blur step's edge handling. */
    val BlurEdgeMode = AltStageBlurEdgeMode.Clamp

    /** Whether the twist step is in the chain (it still needs API 33). */
    const val TwistEnabled = true
    const val Saturation = 1.75f

    /** Adaptive title color, on in every build: dark when the median WCAG luminance is above the threshold. */
    const val AdaptiveTextEnabled = true
    const val AdaptiveTextThreshold = 0.32f
    val LockedAdaptiveTextMetric = AdaptiveTextMetric.WcagLuminance
    val LockedAdaptiveTextStatistic = AdaptiveTextStatistic.Median

    /** Motion updates and redraws at this rate. */
    const val FrameCapFps = 10

    /** The art, blur, twist and saturation run on a layer this many times smaller per side, then scaled up. */
    const val FilterResolutionDivisor = 4

    /** Scrim over the finished background; nothing is drawn while it is 0. */
    const val ScrimAlpha = 0f
}
