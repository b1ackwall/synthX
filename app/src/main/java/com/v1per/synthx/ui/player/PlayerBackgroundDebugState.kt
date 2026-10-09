package com.v1per.synthx.ui.player

import androidx.compose.runtime.mutableStateOf
// DEAD CODE: only the commented-out measuredBrightness used it. Delete in a cleanup pass. Ask  before deleting.
// import com.v1per.synthx.playback.BackgroundBrightness

/** Bounds of the debug blur slider, in dp. */
internal const val PlayerBackgroundBlurMinDp = 0f
internal const val PlayerBackgroundBlurMaxDp = 300f

/** [value] limited to the slider range; a NaN falls back to the locked production blur. */
internal fun clampPlayerBackgroundBlurDp(value: Float): Float =
    if (value.isNaN()) PlayerAltStageStyle.BlurDp
    else value.coerceIn(PlayerBackgroundBlurMinDp, PlayerBackgroundBlurMaxDp)

/** How the blur treats content near the layer edge. */
internal enum class AltStageBlurEdgeMode { Clamp, Mirror, Decal, Repeat }

// DEAD CODE: the adaptive title color is locked in PlayerAltStageStyle, so the debug state no longer carries it. Delete in a cleanup pass. Ask  before deleting.
// internal fun clampPlayerAdaptiveTextThreshold(value: Float): Float =
//     if (value.isNaN()) PlayerAltStageStyle.AdaptiveTextThreshold else value.coerceIn(0f, 1f)
// Debug-only testbed values; the expanded player background reads them in debug builds.
internal data class PlayerBackgroundDebugState(
    val blurRadiusDp: Float = PlayerAltStageStyle.BlurDp,
    val twistEnabled: Boolean = PlayerAltStageStyle.TwistEnabled,
    val blurEdgeMode: AltStageBlurEdgeMode = PlayerAltStageStyle.BlurEdgeMode,
    // DEAD CODE: the adaptive title color is locked in PlayerAltStageStyle, so the debug state no longer carries it. Delete in a cleanup pass. Ask  before deleting.
    // val adaptiveTextEnabled: Boolean = PlayerAltStageStyle.AdaptiveTextEnabled,
    // val adaptiveTextThreshold: Float = PlayerAltStageStyle.AdaptiveTextThreshold,
    // val adaptiveTextMetric: AdaptiveTextMetric = PlayerAltStageStyle.LockedAdaptiveTextMetric,
    // val adaptiveTextStatistic: AdaptiveTextStatistic = PlayerAltStageStyle.LockedAdaptiveTextStatistic,
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

// DEAD CODE: the adaptive title color is locked in PlayerAltStageStyle, so the debug state no longer carries it. Delete in a cleanup pass. Ask  before deleting.
// /** Whether the title color adapts: the debug switch in debug builds, else the locked setting. */
// internal fun effectivePlayerAdaptiveTextEnabled(debug: Boolean, state: PlayerBackgroundDebugState): Boolean =
//     if (debug) state.adaptiveTextEnabled else PlayerAltStageStyle.AdaptiveTextEnabled
// /** The brightness above which the title goes dark: the debug value in debug builds, else the locked one. */
// internal fun effectivePlayerAdaptiveTextThreshold(debug: Boolean, state: PlayerBackgroundDebugState): Float =
//     if (debug) clampPlayerAdaptiveTextThreshold(state.adaptiveTextThreshold) else PlayerAltStageStyle.AdaptiveTextThreshold
// /** The brightness metric: the debug choice in debug builds, else the locked one. */
// internal fun effectivePlayerAdaptiveTextMetric(debug: Boolean, state: PlayerBackgroundDebugState): AdaptiveTextMetric =
//     if (debug) state.adaptiveTextMetric else PlayerAltStageStyle.LockedAdaptiveTextMetric
// /** The brightness statistic: the debug choice in debug builds, else the locked one. */
// internal fun effectivePlayerAdaptiveTextStatistic(
//     debug: Boolean,
//     state: PlayerBackgroundDebugState,
// ): AdaptiveTextStatistic = if (debug) state.adaptiveTextStatistic else PlayerAltStageStyle.LockedAdaptiveTextStatistic
// Process-lifetime state, like HomeBlobDebugStateHolder; nothing is persisted.
internal object PlayerBackgroundDebugStateHolder {
    val state = mutableStateOf(PlayerBackgroundDebugState())

    // DEAD CODE: the sheet's brightness readout is hidden, so nothing reads this. Delete in a cleanup pass. Ask  before deleting.
    // The current song's measured art brightness, shown in the sheet. Not part of reset.
    // val measuredBrightness = mutableStateOf<BackgroundBrightness?>(null)

    fun setBlurRadiusDp(value: Float) {
        state.value = state.value.copy(blurRadiusDp = clampPlayerBackgroundBlurDp(value))
    }

    fun setTwistEnabled(enabled: Boolean) {
        state.value = state.value.copy(twistEnabled = enabled)
    }

    fun setBlurEdgeMode(mode: AltStageBlurEdgeMode) {
        state.value = state.value.copy(blurEdgeMode = mode)
    }

    // DEAD CODE: the adaptive title color is locked in PlayerAltStageStyle, so the debug state no longer carries it. Delete in a cleanup pass. Ask  before deleting.
    // fun setAdaptiveTextEnabled(enabled: Boolean) {
    //     state.value = state.value.copy(adaptiveTextEnabled = enabled)
    // }
    // fun setAdaptiveTextThreshold(value: Float) {
    //     state.value = state.value.copy(adaptiveTextThreshold = clampPlayerAdaptiveTextThreshold(value))
    // }
    // fun setAdaptiveTextMetric(metric: AdaptiveTextMetric) {
    //     state.value = state.value.copy(adaptiveTextMetric = metric)
    // }
    // fun setAdaptiveTextStatistic(statistic: AdaptiveTextStatistic) {
    //     state.value = state.value.copy(adaptiveTextStatistic = statistic)
    // }
    fun reset() {
        state.value = PlayerBackgroundDebugState()
    }
}
