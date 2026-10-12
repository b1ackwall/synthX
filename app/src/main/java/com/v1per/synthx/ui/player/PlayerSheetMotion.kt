package com.v1per.synthx.ui.player

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import com.v1per.synthx.ui.components.PlayerSheetValue

/** The window of the sheet's expand progress across which the player-mode title row fades in. */
internal const val PlayerTitleFadeStart = 0.8f
internal const val PlayerTitleFadeEnd = 1f

/** The window of the sheet's expand progress across which the controls overlay fades in. */
internal const val PlayerOverlayFadeStart = 0.9f
internal const val PlayerOverlayFadeEnd = 1f

/**
 * The window of the sheet's expand progress across which queue mode's header, list and empty
 * text fade in.
 */
internal const val PlayerQueueFadeStart = 0.7f
internal const val PlayerQueueFadeEnd = 1f

/** [progress] remapped linearly so [start] is 0 and [end] is 1, clamped to that range. */
internal fun playerFadeWindowAlpha(progress: Float, start: Float, end: Float): Float =
    ((progress - start) * (1f / (end - start))).coerceIn(0f, 1f)

/**
 * How far below its resting place the controls overlay sits: the sheet's drag offset measured
 * from the expanded anchor ([sheetOffsetPx] - [expandedAnchorPx]), floored at 0, so the overlay
 * travels with the finger. Before the anchors exist either value is NaN, and the overlay
 * instead sits [ContentSlideDistance] lerped to 0dp by [expandedContentAlpha].
 */
internal fun controlsOverlaySlide(
    expandedContentAlpha: Float,
    sheetOffsetPx: Float,
    expandedAnchorPx: Float,
    density: Density,
): Dp {
    val displacementPx = sheetOffsetPx - expandedAnchorPx
    return if (displacementPx.isNaN()) {
        lerpDp(ContentSlideDistance, 0.dp, expandedContentAlpha)
    } else {
        with(density) { displacementPx.coerceAtLeast(0f).toDp() }
    }
}

/** What the sheet hands the title row, the controls overlay and the queue content each frame. */
internal data class PlayerSheetMotion(
    val titleAlpha: Float,
    val overlayAlpha: Float,
    /** How far below its resting place the controls overlay sits. */
    val overlaySlide: Dp,
    /** The collapse/expand term of the queue header, list and empty-text alpha. */
    val queueFadeAlpha: Float,
) {
    val titleEnabled: Boolean get() = titleAlpha > 0.5f
    val overlayEnabled: Boolean get() = overlayAlpha > 0.5f

    /**
     * How strongly the queue list is masked out under the controls: the overlay's own alpha,
     * [overlayVisibleAlpha] being its queue-scroll hide.
     */
    fun maskStrength(overlayVisibleAlpha: Float): Float = overlayAlpha * overlayVisibleAlpha
}

/**
 * The title, overlay and queue alphas over their own windows of [progress], and the overlay's
 * slide (see [controlsOverlaySlide]).
 */
internal fun playerSheetMotion(
    progress: Float,
    expandedContentAlpha: Float,
    sheetOffsetPx: Float,
    expandedAnchorPx: Float,
    density: Density,
): PlayerSheetMotion = PlayerSheetMotion(
    titleAlpha = playerFadeWindowAlpha(progress, PlayerTitleFadeStart, PlayerTitleFadeEnd),
    overlayAlpha = playerFadeWindowAlpha(progress, PlayerOverlayFadeStart, PlayerOverlayFadeEnd),
    overlaySlide = controlsOverlaySlide(expandedContentAlpha, sheetOffsetPx, expandedAnchorPx, density),
    queueFadeAlpha = playerFadeWindowAlpha(progress, PlayerQueueFadeStart, PlayerQueueFadeEnd),
)

/** The sheet's entry point: reads its live drag offset and expanded anchor. */
internal fun playerSheetMotion(
    progress: Float,
    expandedContentAlpha: Float,
    draggableState: AnchoredDraggableState<PlayerSheetValue>,
    density: Density,
): PlayerSheetMotion = playerSheetMotion(
    progress = progress,
    expandedContentAlpha = expandedContentAlpha,
    sheetOffsetPx = draggableState.offset,
    expandedAnchorPx = draggableState.anchors.positionOf(PlayerSheetValue.Expanded),
    density = density,
)
