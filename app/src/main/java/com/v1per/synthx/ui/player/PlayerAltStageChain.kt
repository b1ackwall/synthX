package com.v1per.synthx.ui.player

/** One step of the background's effect chain. */
internal enum class AltStageStep { Blur, Twist, Saturation }

/**
 * The chain from the layer content outward: blur, then twist, then saturation. The blur runs only
 * for a positive radius, and the twist only when it is enabled and the device supports it.
 */
internal fun altStageChain(blurPx: Float, twistEnabled: Boolean, twistSupported: Boolean): List<AltStageStep> =
    buildList {
        if (blurPx > 0f) add(AltStageStep.Blur)
        if (twistEnabled && twistSupported) add(AltStageStep.Twist)
        add(AltStageStep.Saturation)
    }
