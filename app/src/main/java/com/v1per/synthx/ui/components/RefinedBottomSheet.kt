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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
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
 * Ceiling on the sheet's own height, as a fraction of the height actually available at runtime
 * (never a fixed dp, so it scales with the device instead of over/under-shooting small and large
 * screens differently).
 *
 * Without it the sheet is purely content-sized with no upper bound at all: a long enough list
 * (the playlist picker on a large library, the group picker below) grows all the way to the top
 * of the window and — since this sheet applies no window insets, the app being edge-to-edge —
 * slides its first rows under the status bar. Capping here rather than at each call site fixes
 * it once for every consumer; a list that exceeds the cap simply scrolls inside its own
 * LazyColumn, and anything shorter than the cap is unaffected and still sizes to its content.
 */
private const val MaxHeightFraction = 0.7f

/**
 * Floor on the sheet's own height, same fraction-of-available-height reasoning as
 * [MaxHeightFraction]. Without it a very short list (e.g. a 2-artist "go to artist" picker)
 * shrinks the sheet down to just its content size, which reads as a cramped, oddly small sheet
 * hugging the bottom of the screen. Content shorter than this floor is padded up to it rather
 * than the sheet shrinking to fit; content between the floor and [MaxHeightFraction] is
 * unaffected either way.
 */
private const val MinHeightFraction = 0.3f

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
 * - Drag-to-dismiss (only when [enableDragToDismiss] is true): velocity-based slide only (no
 *   fade, no fixed duration) — a target-based spring ([Motion.SpringSnappy]) animates to fully
 *   closed with the release velocity fed in as the spring's initial velocity, so a fast flick
 *   still feels fast without ever stalling short of closed the way a pure decay animation can on
 *   a slow release.
 *
 * [enableDragToDismiss] defaults to true to preserve prior behavior for any future consumer that
 * wants it, but every current consumer (SongActionsBottomSheet, GroupPickerBottomSheet,
 * SortBottomSheet) disables it: the drag gesture is applied to the whole outer Column, so it sits
 * underneath any scrollable content() (a LazyColumn on a sub-page or picker) and loses the touch
 * to that list's own scrolling whenever the list can still scroll in that direction — an
 * unresolved nestedScroll conflict that made drag-to-dismiss behave inconsistently depending on
 * list length/scroll position/touch location. Scrim-tap and system back remain the dismiss paths
 * for those consumers, both already independent of this gesture.
 *
 * Accessibility: the scrim is reachable as a labeled dismiss target and system back closes the
 * sheet via [BackHandler]. Since there's no separate Dialog window to give `ModalBottomSheet`'s
 * focus-trapping for free, this sheet's content is grouped into one accessibility node via
 * `Modifier.semantics { isTraversalGroup = true }` below, and callers are expected to pair the
 * sheet's open/closed condition with [hiddenFromAccessibilityWhileSheetShown] on whatever they
 * compose behind/alongside it (see that function's kdoc) so TalkBack can't wander into content
 * behind the scrim while the sheet is open.
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
    enableDragToDismiss: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val velocityThresholdPx = with(density) { 200.dp.toPx() }
    val dismissLabel = stringResource(R.string.cd_close)

    BackHandler(onBack = onDismissRequest)

    // This sheet lives in the Activity window and applies no IME insets, so a text field that
    // held focus when it opened (Search's field, a playlist's inline rename field) would keep
    // the keyboard up over the sheet content. Clearing focus, not just hiding the IME, also
    // stops the field re-showing the keyboard when the sheet closes. Both calls are no-ops when
    // nothing is focused / the keyboard is already hidden.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

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
                // Read from this same BoxWithConstraints scope that already supplies heightPx
                // above, so the floor/cap track the real available height — see
                // MinHeightFraction/MaxHeightFraction.
                .heightIn(min = maxHeight * MinHeightFraction, max = maxHeight * MaxHeightFraction)
                // Groups the sheet's own nodes into one accessibility region distinct from
                // whatever's behind it — see this composable's kdoc.
                .semantics { isTraversalGroup = true }
                .graphicsLayer {
                    translationY = sheetState.offsetY.value
                    alpha = sheetState.alpha.value
                }
                .then(
                    if (enableDragToDismiss) {
                        Modifier.draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                val next = (sheetState.offsetY.value + delta).coerceIn(0f, heightPx)
                                scope.launch { sheetState.offsetY.snapTo(next) }
                            },
                            onDragStopped = { velocity ->
                                val positionalThreshold = heightPx * 0.4f
                                if (sheetState.offsetY.value > positionalThreshold || velocity > velocityThresholdPx) {
                                    sheetState.markHiddenByDrag()
                                    // Target-based, not animateDecay: decay has no target position
                                    // and only coasts on the release velocity, so a slow release
                                    // with little velocity to coast on stalls short of fully
                                    // closed. Feeding the release velocity in as the spring's
                                    // initialVelocity keeps a fast flick feeling fast while
                                    // guaranteeing every release still animates all the way to
                                    // fully closed in one continuous motion.
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
                    } else {
                        Modifier
                    },
                )
                .clip(shape)
                .background(containerColor),
        ) {
            if (showDragHandle) {
                RefinedSheetDragHandle()
            } else {
                // The handle isn't purely a drag affordance — its own vertical padding plus the
                // pill (Spacing.sm + 4.dp + Spacing.sm) was incidentally the only top breathing
                // room every consumer's content had above it. Consumers that hide the handle
                // (all current ones) still need *some* top spacing of their own, or content sits
                // flush against the sheet's clipped top edge. Spacing.md rather than Spacing.sm
                // because at least two consumers (ActionsPage, SortBottomSheet) have zero top
                // padding of their own on their header content, so this spacer is their only
                // source of top spacing and needs to read as adequate on its own, not just a
                // top-up on top of an existing caption padding.
                Spacer(modifier = Modifier.height(Spacing.md))
            }
            content()
        }
    }
}

/**
 * Removes this subtree from the accessibility tree entirely while [hidden] is true — pass the
 * same condition that shows a [RefinedBottomSheet] to whatever's composed behind/alongside it,
 * so TalkBack can't traverse into it while the sheet covers it. This is the documented Compose
 * mechanism for excluding on-screen content from the semantics tree by hand (an empty
 * [clearAndSetSemantics] block clears all descendant semantics — the Compose equivalent of the
 * View system's `importantForAccessibility = NO_HIDE_DESCENDANTS`); a real Dialog/Popup window
 * gets this for free by being a separate accessibility-focus root, which is exactly the behavior
 * [RefinedBottomSheet] deliberately opts out of (see its kdoc) to avoid `ModalBottomSheet`'s
 * app-wide touch-blocking, so it has to be reproduced by hand instead.
 */
fun Modifier.hiddenFromAccessibilityWhileSheetShown(hidden: Boolean): Modifier =
    if (hidden) clearAndSetSemantics {} else this

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
