package com.v1per.synthx.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.v1per.synthx.R
import com.v1per.synthx.ui.theme.Hairline
import com.v1per.synthx.ui.theme.Motion
import com.v1per.synthx.ui.theme.Spacing
import com.v1per.synthx.ui.theme.Surface as SurfaceColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Drives one [RefinedBottomSheet]'s open/close lifecycle. Created once per call site (e.g.
 * via [rememberRefinedBottomSheetState]) and reused across repeated show/hide cycles, mirroring
 * how [androidx.compose.material3.SheetState] is reused across a `ModalBottomSheet`'s lifetime —
 * existing call sites that already do `scope.launch { sheetState.hide() }.invokeOnCompletion {}`
 * keep working unchanged against this type.
 */
@Stable
class RefinedBottomSheetState {
    internal val offsetY = Animatable(0f)
    internal val alpha = Animatable(0f)

    /** Distance to fully off-screen — the screen height, so a drag always has room to travel. */
    internal var heightPx = 0f

    /** True once fully hidden (or never yet shown) — guards [show]/[hide] against re-entry. */
    private var isHidden = true

    internal suspend fun show() {
        if (!isHidden) return
        isHidden = false
        offsetY.snapTo(heightPx)
        coroutineScope {
            launch { alpha.animateTo(1f, tween(Motion.DurationNormal, easing = Motion.EasingDecelerate)) }
            launch { offsetY.animateTo(0f, tween(Motion.DurationNormal, easing = Motion.EasingDecelerate)) }
        }
    }

    /**
     * Animates out with the tap-outside spec (fade + slide together) and suspends until done.
     * A no-op if the sheet is already hidden — including when a drag already carried it off
     * screen via [markHiddenByDrag], so callers that unconditionally call this after their own
     * dismiss trigger (matching the pre-existing `dismiss()` pattern at call sites) never
     * double-animate.
     */
    suspend fun hide() {
        if (isHidden) return
        isHidden = true
        coroutineScope {
            launch { alpha.animateTo(0f, tween(Motion.DurationNormal, easing = Motion.EasingAccelerate)) }
            launch { offsetY.animateTo(heightPx, tween(Motion.DurationNormal, easing = Motion.EasingAccelerate)) }
        }
    }

    /**
     * Called by [RefinedBottomSheet] once a drag-to-dismiss gesture has finished carrying the
     * sheet off screen. The drag already moved [offsetY]/[alpha] itself (no fade, velocity-based
     * slide) — this just marks [hide] as a no-op so a caller's subsequent `hide()` call doesn't
     * replay the tween on top of it.
     */
    internal fun markHiddenByDrag() {
        isHidden = true
    }
}

@Composable
fun rememberRefinedBottomSheetState(): RefinedBottomSheetState = remember { RefinedBottomSheetState() }

/**
 * A bottom sheet that lives in normal composition — no [androidx.compose.ui.window.Dialog] or
 * Popup wrapping. `ModalBottomSheet` renders inside its own Android Window, which blocks touches
 * app-wide for the entire close animation regardless of visual state; this sheet has no such
 * window, so touch capture is scoped exactly to the scrim/sheet nodes actually in the tree.
 *
 * Must be composed as the last child inside a [Box] (or equivalent) that also contains the
 * screen content it should sit above — like [androidx.compose.ui.window.Dialog], nothing about
 * this composable enforces z-order on its own; unlike Dialog, it relies on normal composition
 * order to draw on top.
 *
 * Animation contract:
 * - Open and tap-outside-close: fade + slide together, `tween(`[Motion.DurationNormal]`)`,
 *   decelerate/accelerate easing.
 * - Drag-to-dismiss: velocity-based slide only (no fade, no fixed duration) — a target-based
 *   spring ([Motion.SpringSnappy]) animates to fully closed with the release velocity fed in as
 *   the spring's initial velocity, so a fast flick still feels fast without ever stalling short
 *   of closed the way a pure decay animation can on a slow release.
 *
 * Accessibility: the scrim is reachable as a labeled dismiss target and system back closes the
 * sheet via [BackHandler], but this does *not* replicate `ModalBottomSheet`'s window-level focus
 * trapping — TalkBack can still navigate into content behind the scrim while the sheet is open,
 * since everything lives in one shared accessibility tree rather than a separate Dialog window.
 */
@Composable
fun RefinedBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: RefinedBottomSheetState,
    modifier: Modifier = Modifier,
    containerColor: Color = SurfaceColor,
    scrimColor: Color = Color.Black.copy(alpha = 0.32f),
    shape: Shape = MaterialTheme.shapes.small,
    showDragHandle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val velocityThresholdPx = with(density) { 200.dp.toPx() }
    val dismissLabel = stringResource(R.string.cd_close)

    BackHandler(onBack = onDismissRequest)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val heightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(heightPx) {
            if (heightPx > 0f) {
                sheetState.heightPx = heightPx
                sheetState.show()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = sheetState.alpha.value }
                .background(scrimColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = dismissLabel,
                    role = Role.Button,
                    onClick = onDismissRequest,
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = sheetState.offsetY.value
                    alpha = sheetState.alpha.value
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        val next = (sheetState.offsetY.value + delta).coerceIn(0f, heightPx)
                        scope.launch { sheetState.offsetY.snapTo(next) }
                    },
                    onDragStopped = { velocity ->
                        val positionalThreshold = heightPx * 0.4f
                        if (sheetState.offsetY.value > positionalThreshold || velocity > velocityThresholdPx) {
                            sheetState.markHiddenByDrag()
                            // Target-based, not animateDecay: decay has no target position and
                            // only coasts on the release velocity, so a slow release with little
                            // velocity to coast on stalls short of fully closed. Feeding the
                            // release velocity in as the spring's initialVelocity keeps a fast
                            // flick feeling fast while guaranteeing every release still animates
                            // all the way to fully closed in one continuous motion.
                            sheetState.offsetY.animateTo(
                                targetValue = heightPx,
                                animationSpec = Motion.SpringSnappy,
                                initialVelocity = velocity,
                            )
                            onDismissRequest()
                        } else {
                            sheetState.offsetY.animateTo(0f, Motion.SpringStandard)
                        }
                    },
                )
                .clip(shape)
                .background(containerColor),
        ) {
            if (showDragHandle) {
                RefinedSheetDragHandle()
            }
            content()
        }
    }
}

@Composable
private fun RefinedSheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Hairline),
        )
    }
}
