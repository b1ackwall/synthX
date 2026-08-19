package com.v1per.synthx.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Queue mode's toggle state, its crossfade progress against the collapse/expand axis, the
 * queue list's own scroll state, and the derived controls-overlay visibility — everything
 * PlayerSheetSkeleton and the content files it wires together need to read for queue mode.
 * Built once per composition by [rememberQueueModeState]; not intended to be constructed
 * directly (hence the internal constructor).
 */
@Stable
class QueueModeState internal constructor(
    val queueProgress: Animatable<Float, AnimationVector1D>,
    val queueListState: LazyListState,
    private val scope: CoroutineScope,
) {
    var isQueueMode: Boolean by mutableStateOf(false)
        internal set

    var controlsVisible: Boolean by mutableStateOf(true)
        internal set

    // Set by the shuffle click handler below, consumed by rememberQueueModeState's
    // LaunchedEffect(upcomingQueue) once the reordered queue has actually landed in this
    // composable's `upcomingQueue` parameter — see that effect's doc comment for why the
    // scroll-to-top can't just fire inline in the click handler.
    internal var pendingShuffleScrollReset: Boolean by mutableStateOf(false)

    // Refreshed every recomposition by rememberQueueModeState (a plain field write, not
    // `remember`-ed) so onShuffleClickWithScrollReset below always dispatches to the caller's
    // current callback rather than whichever one was live when this object was constructed.
    // This is the mechanism that replaces what the original inline implementation achieved by
    // simply rebuilding a fresh (non-remembered) wrapper lambda every recomposition — needed
    // because that wrapper "needs to close over the current onShuffleClick every time", and
    // this object itself is now remembered once rather than rebuilt each pass.
    internal var onShuffleClick: () -> Unit = {}

    // Stable-identity click handler: this object (and therefore this lambda) is remembered
    // once by rememberQueueModeState rather than declared inline at the button's call site.
    // `dragResistantClickable`'s pointerInput is keyed on (interactionSource, enabled,
    // onClick) — an inline lambda there would get a new identity every recomposition, and
    // PlayerSheetSkeleton recomposes every animation frame while `progress` is mid-spring,
    // which was restarting the gesture coroutine repeatedly during a tap-triggered expand and
    // eating same-frame taps on the queue button (confirmed root cause). This lambda still
    // reads isQueueMode/queueProgress/scope through their stable, remembered delegate/object
    // references at invocation time, not via captured-by-value snapshots, so it always acts on
    // current state despite being constructed only once.
    val onQueueClick: () -> Unit = {
        isQueueMode = !isQueueMode
        scope.launch {
            queueProgress.animateTo(
                if (isQueueMode) 1f else 0f,
                animationSpec = tween(Motion.DurationNormal, easing = Motion.EasingStandard),
            )
        }
    }

    // Shuffle reorders upcomingQueue (see controlsVisible's doc comment in
    // rememberQueueModeState below), which leaves queueListState visually anchored to
    // wherever the previously-topmost song ended up in the new order, instead of back at the
    // top. The actual scrollToItem(0) call is deferred to rememberQueueModeState's
    // LaunchedEffect(upcomingQueue) (see its doc comment for why firing it inline here races
    // the async queue-reorder propagation) — this handler only arms that effect via
    // pendingShuffleScrollReset. controlsVisible is still set directly here rather than
    // deferred: it's a plain local write with nothing to race — no dependency on the queue's
    // new content having landed.
    val onShuffleClickWithScrollReset: () -> Unit = {
        onShuffleClick()
        controlsVisible = true
        pendingShuffleScrollReset = true
    }
}

/**
 * Remembers and wires up all queue-mode state for [PlayerSheetSkeleton]: the queue-mode
 * toggle + its crossfade [Animatable], the queue list's [LazyListState], and the
 * scroll-derived controls-overlay visibility with its shuffle-reorder scroll-reset fix.
 *
 * [scope] is the same [CoroutineScope] PlayerSheetSkeleton already holds via
 * `rememberCoroutineScope()` for its own drag-settle/expand animations — reused here rather
 * than creating a second one, so every animation this composable drives shares one scope.
 * [upcomingQueue] and [onShuffleClick] are the raw value/callback PlayerSheetSkeleton receives
 * as its own parameters, threaded through unchanged.
 */
@Composable
fun rememberQueueModeState(
    scope: CoroutineScope,
    upcomingQueue: List<Song>,
    onShuffleClick: () -> Unit,
): QueueModeState {
    val density = LocalDensity.current
    val queueProgress = remember { Animatable(0f) }

    // Hoisted so both QueueList's own scrolling and this composable's derived
    // controlsVisible below observe the same LazyListState — mirrors the old (dead)
    // PlayerExpandedContent.kt's hoisting of queueListState for the identical purpose.
    val queueListState = rememberLazyListState()

    val state = remember { QueueModeState(queueProgress, queueListState, scope) }
    state.onShuffleClick = onShuffleClick

    val collapseThresholdPx = with(density) { ControlsCollapseThreshold.toPx() }

    // Only meaningful in queue mode: player mode always shows the overlay (the
    // `!isQueueMode ||` in PlayerSkeletonControlsOverlay's call site).
    //
    // Deliberately NOT a derivedStateOf reading queueListState directly (the old approach):
    // Compose's LazyListState does key-based scroll-position correction — when the *content*
    // of a keyed list reorders under a stationary viewport (e.g. a shuffle toggle reshuffling
    // upcomingQueue), it relabels firstVisibleItemIndex to wherever the previously-topmost
    // song's key ended up, to keep that song visually anchored. That's correct LazyListState
    // behavior, but it reads exactly like "the user scrolled away from the top" to a naive
    // index == 0 check, even though nothing was touched. Gating writes on isScrollInProgress
    // restricts this state to only ever update in response to a real user-driven gesture (drag
    // and the fling it releases into both hold isScrollInProgress true the whole time) — a
    // reorder-triggered remeasure happens outside any gesture, so it can no longer flip this.
    // Starts true (top, unscrolled) before any scroll has ever occurred; holds its last
    // live-gesture value once a gesture ends, rather than reacting to whatever queueListState
    // reports afterward.
    LaunchedEffect(queueListState) {
        snapshotFlow {
            Triple(
                queueListState.isScrollInProgress,
                queueListState.firstVisibleItemIndex,
                queueListState.firstVisibleItemScrollOffset,
            )
        }.collect { (inProgress, index, offset) ->
            if (inProgress) {
                state.controlsVisible = index == 0 && offset < collapseThresholdPx
            }
        }
    }

    // Defers queueListState.scrollToItem(0) until upcomingQueue has actually changed following
    // a shuffle click, instead of firing it inline in the click handler. onShuffleClick()
    // synchronously writes the engine's upcomingQueue StateFlow, but that write only reaches
    // this composable's `upcomingQueue` parameter once collectAsState's collector coroutine (in
    // SynthXBottomNav.kt) resumes and a recomposition applies the new value — neither happens
    // within the click handler's own call stack. A scrollToItem(0) fired inline there races
    // that propagation and, in practice, usually wins: it scrolls the *old* (pre-shuffle) list,
    // and once the real reordered list lands moments later, Compose's key-based
    // scroll-position correction (the mechanism controlsVisible's own doc comment above
    // describes) relocates the viewport to wherever the old top song ended up in the new order
    // — an arbitrary position, not the top (confirmed root cause of the reported "lands
    // somewhere odd"). Keying this effect on `upcomingQueue` itself, rather than a manual
    // counter/signal, guarantees it only runs once the queue this composable actually observes
    // has changed; LaunchedEffect cancels and restarts on every key change, so it can't
    // double-fire for the same change. `pendingShuffleScrollReset` scopes it to shuffle
    // specifically — any other queue change (drag-reorder, add/remove) leaves the flag false
    // and this effect no-ops.
    //
    // Known edge case: if a shuffle happens to produce byte-identical ordering (only
    // realistically possible on very small queues), upcomingQueue's StateFlow won't re-emit a
    // structurally-different value, this effect never reruns, and the flag stays pending until
    // the next unrelated queue change consumes it — a rare, low-severity false-positive
    // scroll-to-top, not a crash or data issue. Not guarded against here; flagged for awareness
    // rather than added complexity, since it requires an already rare shuffle outcome on an
    // already-small queue.
    LaunchedEffect(upcomingQueue) {
        if (state.pendingShuffleScrollReset) {
            state.pendingShuffleScrollReset = false
            queueListState.scrollToItem(0)
        }
    }

    return state
}
