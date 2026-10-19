package com.v1per.synthx.ui.player

import androidx.compose.runtime.mutableStateOf

/** Bounds of the debug blur slider, in dp. */
internal const val PlayerBackgroundBlurMinDp = 0f
internal const val PlayerBackgroundBlurMaxDp = 300f

/** [value] limited to the slider range; a NaN falls back to the locked production blur. */
internal fun clampPlayerBackgroundBlurDp(value: Float): Float = if (value.isNaN()) {
    PlayerAltStageStyle.BlurDp
} else {
    value.coerceIn(PlayerBackgroundBlurMinDp, PlayerBackgroundBlurMaxDp)
}

/** How the blur treats content near the layer edge. */
internal enum class AltStageBlurEdgeMode { Clamp, Mirror, Decal, Repeat }

// Debug-only testbed values; the expanded player background reads them in debug builds.
internal data class PlayerBackgroundDebugState(
    val blurRadiusDp: Float = PlayerAltStageStyle.BlurDp,
    val twistEnabled: Boolean = PlayerAltStageStyle.TwistEnabled,
    val blurEdgeMode: AltStageBlurEdgeMode = PlayerAltStageStyle.BlurEdgeMode,
)

/** The blur the player background draws with: the debug value in debug builds, else the locked one. */
internal fun effectivePlayerBackgroundBlurDp(debug: Boolean, state: PlayerBackgroundDebugState): Float =
    if (debug) clampPlayerBackgroundBlurDp(state.blurRadiusDp) else PlayerAltStageStyle.BlurDp

/** Whether the twist is in the chain: the debug switch in debug builds, else the locked setting. */
internal fun effectivePlayerBackgroundTwistEnabled(debug: Boolean, state: PlayerBackgroundDebugState): Boolean =
    if (debug) state.twistEnabled else PlayerAltStageStyle.TwistEnabled

/** The blur's edge mode: the debug choice in debug builds, else the locked one. */
internal fun effectivePlayerBackgroundBlurEdgeMode(
    debug: Boolean,
    state: PlayerBackgroundDebugState,
): AltStageBlurEdgeMode = if (debug) state.blurEdgeMode else PlayerAltStageStyle.BlurEdgeMode

// Process-lifetime state, like HomeBlobDebugStateHolder; nothing is persisted.
internal object PlayerBackgroundDebugStateHolder {
    val state = mutableStateOf(PlayerBackgroundDebugState())

    fun setBlurRadiusDp(value: Float) {
        state.value = state.value.copy(blurRadiusDp = clampPlayerBackgroundBlurDp(value))
    }

    fun setTwistEnabled(enabled: Boolean) {
        state.value = state.value.copy(twistEnabled = enabled)
    }

    fun setBlurEdgeMode(mode: AltStageBlurEdgeMode) {
        state.value = state.value.copy(blurEdgeMode = mode)
    }

    fun reset() {
        state.value = PlayerBackgroundDebugState()
    }
}
