package com.v1per.synthx.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Queue mode's toggle state, its crossfade progress against the collapse/expand axis, the
 * queue list's own scroll state, and the gesture-derived controls-overlay visibility —
 * everything PlayerSheetSkeleton and the content files it wires together need to read for
 * queue mode. Built once per composition by [rememberQueueModeState]; not intended to be
 * constructed directly (hence the internal constructor).
 */
@Stable
class QueueModeState internal constructor(
    val queueProgress: Animatable<Float, AnimationVector1D>,
    val queueListState: LazyListState,
    private val scope: CoroutineScope,
    private val overlayToggleThresholdPx: Float,
    // Owned by rememberQueueModeState's rememberSaveable, not this class, so only this one
    // boolean survives a configuration change — queueProgress/queueListState/scope are not
    // meaningfully saveable (Animatable/LazyListState/CoroutineScope), and controlsVisible/
    // the scroll-gesture bookkeeping below are transient gesture state not worth restoring.
    private val isQueueModeState: MutableState<Boolean>,
) {
    var isQueueMode: Boolean
        get() = isQueueModeState.value
        internal set(value) {
            isQueueModeState.value = value
        }

    /**
     * Whether the controls overlay is showing. Hidden only by a net upward drag on the queue
     * list ([queueScrollConnection]); restored by a net downward one, by the shuffle handler
     * below, and — unconditionally — whenever the queue is empty. See the
     * `LaunchedEffect(upcomingQueue.isEmpty())` in [rememberQueueModeState] for why that last
     * one is load-bearing rather than cosmetic: with an empty queue there is no list left to
     * drag on, so a `false` latched here would otherwise be permanent.
     */
    var controlsVisible: Boolean by mutableStateOf(true)
        internal set

    // Running net drag delta since the last threshold crossing, in px. Not Compose state —
    // written and read only from onPreScroll, which nested-scroll dispatch always calls on
    // the same (main) thread, so there's no concurrent-access concern requiring a snapshot.
    private var accumulatedScrollDelta = 0f

    /**
     * Gesture-direction-based `controlsVisible`: a net upward drag (accumulated `available.y` past
     * `-overlayToggleThresholdPx`) hides the overlay, a net downward drag past
     * `+overlayToggleThresholdPx` shows it again. Threaded down to [QueueList]'s modifier via
     * `Modifier.nestedScroll` in PlayerSkeletonQueueContent, so this fires on real drag deltas
     * regardless of whether the list itself has room to scroll — a scroll-position signal
     * (`isScrollInProgress`/`firstVisibleItemIndex`) fails when the queue is short enough
     * that the list can never actually scroll away from the top, permanently hiding rows
     * underneath the controls overlay with no way to reveal them.
     *
     * `onPreScroll` (not `onPostScroll`) so this observes the raw drag delta before the child
     * `LazyColumn` consumes any of it — consumption doesn't matter here since this connection
     * never uses the return value to steal scroll, it only observes and always returns
     * `Offset.Zero`.
     */
    val queueScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            accumulatedScrollDelta += available.y
            when {
                accumulatedScrollDelta <= -overlayToggleThresholdPx -> {
                    controlsVisible = false
                    accumulatedScrollDelta = 0f
                }
                accumulatedScrollDelta >= overlayToggleThresholdPx -> {
                    controlsVisible = true
                    accumulatedScrollDelta = 0f
                }
            }
            return Offset.Zero
        }
    }

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
    // which would restart the gesture coroutine repeatedly during a tap-triggered expand and
    // eat same-frame taps on the queue button. This lambda still
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

    // Shuffle reorders upcomingQueue (see the LaunchedEffect(upcomingQueue) doc comment in
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
 * gesture-derived controls-overlay visibility with its shuffle-reorder scroll-reset fix.
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
    upcomingQueue: List<QueueSlot>,
    onShuffleClick: () -> Unit,
): QueueModeState {
    val density = LocalDensity.current
    val queueProgress = remember { Animatable(0f) }

    // Hoisted so both QueueList's own scrolling and this composable's derived
    // controlsVisible below observe the same LazyListState.
    val queueListState = rememberLazyListState()

    // Small threshold (tuned to feel, not any existing design constant) a net drag must cross
    // before queueScrollConnection toggles controlsVisible — see that property's doc comment
    // in the QueueModeState class above for the full mechanism.
    val overlayToggleThresholdPx = with(density) { 28.dp.toPx() }

    // The only piece of queue-mode state worth surviving a configuration change — see the
    // isQueueModeState kdoc on QueueModeState above.
    val isQueueModeState = rememberSaveable { mutableStateOf(false) }

    val state = remember {
        QueueModeState(queueProgress, queueListState, scope, overlayToggleThresholdPx, isQueueModeState)
    }
    state.onShuffleClick = onShuffleClick

    // Restores the controls overlay whenever the queue has nothing left to scroll on.
    //
    // The only path that sets controlsVisible = false is queueScrollConnection.onPreScroll, and
    // that connection is attached to QueueList's own modifier — but PlayerSkeletonQueueContent
    // swaps QueueList out of composition entirely, for a static "Nothing queued after this" Box
    // with no gesture handling at all, the moment upcomingQueue goes empty. So the hide becomes
    // one-way from there: no list, no nested-scroll dispatch, no way back to true. And with the
    // overlay hidden, PlayerSkeletonControlsOverlay drops its own controls (and its touch shield)
    // out of composition once overlayVisibleAlpha reaches 0f, while PlayerSheetSkeleton's sheet
    // detector already declines every touch below queueListOffsetY while in queue mode — so the
    // whole lower region goes inert and the user is left with no transport controls and no
    // gesture that can bring them back.
    //
    // Keyed on the emptiness boolean rather than on upcomingQueue itself so it re-runs exactly
    // when that condition flips, not on every unrelated queue edit. Because LaunchedEffect also
    // runs its body on first composition, this equally covers the "already empty" case, not just
    // the live non-empty → empty transition — which matters because controlsVisible is a plain
    // remembered field: deliberately not rememberSaveable (see isQueueModeState's kdoc above),
    // but it does survive leaving queue mode, collapsing the sheet, and navigating to a route
    // where PlayerSheetSkeleton is merely zero-sized rather than removed from composition, so a
    // stale false can outlive the interaction that set it and still be waiting on re-entry.
    //
    // Deliberately a separate effect from the LaunchedEffect(upcomingQueue) below rather than
    // folded into it: that one is scoped to the shuffle scroll-reset race and keyed accordingly,
    // and the two conditions are unrelated.
    //
    // Only ever writes `true` — hiding stays entirely gesture-driven, and this branch cannot run
    // while upcomingQueue has items, so a populated queue's scroll-to-hide/show is unaffected.
    LaunchedEffect(upcomingQueue.isEmpty()) {
        if (upcomingQueue.isEmpty()) {
            state.controlsVisible = true
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
    // scroll-position correction — a keyed LazyListState relabels firstVisibleItemIndex to
    // wherever a stationary-viewport item's key ended up after its content reorders —
    // relocates the viewport to wherever the old top song ended up in the new order, an
    // arbitrary position, not the top. Keying this effect on `upcomingQueue` itself, rather than a manual
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
