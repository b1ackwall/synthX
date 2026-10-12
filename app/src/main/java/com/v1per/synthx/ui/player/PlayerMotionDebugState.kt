package com.v1per.synthx.ui.player

// DEAD CODE: the overlay slide moved to PlayerSheetMotion.kt. Delete in a cleanup pass. Ask  before deleting.
// import androidx.compose.ui.unit.dp
// import androidx.compose.ui.unit.lerp as lerpDp

// DEAD CODE: the player-mode fade windows are locked in PlayerSheetMotion.kt. Delete in a cleanup pass. Ask  before deleting.
// internal const val PlayerFadeStartDefault = 0.4f
// internal const val PlayerFadeEndDefault = 1f

    // DEAD CODE: the player-mode values are locked in PlayerSheetMotion.kt. Delete in a cleanup pass. Ask  before deleting.
    // val overlayFadeStart: Float = PlayerFadeStartDefault,
    // val overlayFadeEnd: Float = PlayerFadeEndDefault,
    // val titleFadeStart: Float = PlayerFadeStartDefault,
    // val titleFadeEnd: Float = PlayerFadeEndDefault,
    // val attachOverlayToSheet: Boolean = false,

// DEAD CODE: moved to PlayerSheetMotion.kt unchanged. Delete in a cleanup pass. Ask  before deleting.
// internal fun playerFadeWindowAlpha(progress: Float, start: Float, end: Float): Float =
//     ((progress - start) * (1f / (end - start))).coerceIn(0f, 1f)

    // DEAD CODE: the title/overlay windows and the attached slide are locked in PlayerSheetMotion.kt. Delete in a cleanup pass. Ask  before deleting.
    // val displacementPx = sheetOffsetPx - expandedAnchorPx
    // return PlayerMotionTuning(
    //     titleAlpha = playerFadeWindowAlpha(progress, state.titleFadeStart, state.titleFadeEnd),
    //     overlayAlpha = playerFadeWindowAlpha(progress, state.overlayFadeStart, state.overlayFadeEnd),
    //     overlaySlide = if (state.attachOverlayToSheet && !displacementPx.isNaN()) {
    //         with(density) { displacementPx.coerceAtLeast(0f).toDp() }
    //     } else {
    //         lerpDp(ContentSlideDistance, 0.dp, expandedContentAlpha)
    //     },
    // )

    // DEAD CODE: the player-mode values are locked in PlayerSheetMotion.kt. Delete in a cleanup pass. Ask  before deleting.
    // fun setOverlayFadeStart(value: Float) {
    //     val start = clampPlayerFadeStart(value)
    //     val end = state.value.overlayFadeEnd.coerceAtLeast(start + PlayerFadeMinWindow)
    //     state.value = state.value.copy(overlayFadeStart = start, overlayFadeEnd = end)
    // }
    // fun setOverlayFadeEnd(value: Float) {
    //     val end = clampPlayerFadeEnd(value)
    //     val start = state.value.overlayFadeStart.coerceAtMost(end - PlayerFadeMinWindow)
    //     state.value = state.value.copy(overlayFadeStart = start, overlayFadeEnd = end)
    // }
    // fun setTitleFadeStart(value: Float) {
    //     val start = clampPlayerFadeStart(value)
    //     val end = state.value.titleFadeEnd.coerceAtLeast(start + PlayerFadeMinWindow)
    //     state.value = state.value.copy(titleFadeStart = start, titleFadeEnd = end)
    // }
    // fun setTitleFadeEnd(value: Float) {
    //     val end = clampPlayerFadeEnd(value)
    //     val start = state.value.titleFadeStart.coerceAtMost(end - PlayerFadeMinWindow)
    //     state.value = state.value.copy(titleFadeStart = start, titleFadeEnd = end)
    // }
    // fun setAttachOverlayToSheet(attach: Boolean) {
    //     state.value = state.value.copy(attachOverlayToSheet = attach)
    // }
