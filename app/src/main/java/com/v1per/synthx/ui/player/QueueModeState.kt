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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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
    // Owned by rememberQueueModeState's rememberSaveable, not this class, so this boolean survives
    // a configuration change and queueProgress starts from it. queueProgress, queueListState's
    // holder and scope are not saveable themselves, and controlsVisible and the drag bookkeeping
    // below are transient gesture state.
    private val isQueueModeState: MutableState<Boolean>,
) {
    var isQueueMode: Boolean
        get() = isQueueModeState.value
        internal set(value) {
            isQueueModeState.value = value
        }

    private var controlsVisibleState by mutableStateOf(true)

    /**
     * Whether the controls overlay is showing. Hidden only by a net upward drag on the queue
     * list ([queueScrollConnection]); shown by a net downward one, by [onShuffleButtonClick], and —
     * unconditionally — whenever the queue is empty. See the `LaunchedEffect(upcomingQueue.isEmpty())`
     * in [rememberQueueModeState] for why that last one is load-bearing rather than cosmetic: with
     * an empty queue there is no list left to drag on, so a `false` latched here would otherwise be
     * permanent.
     *
     * Every write, from a drag or from code, starts the drag count from zero, so a drag after it is
     * measured on its own.
     */
    var controlsVisible: Boolean
        get() = controlsVisibleState
        internal set(value) {
            controlsVisibleState = value
            accumulatedScrollDelta = 0f
        }

    // Net drag delta of the current gesture since its start or the last toggle, in px. Not Compose
    // state: read and written only by the nested-scroll callbacks and the setter above, all on the
    // main thread.
    private var accumulatedScrollDelta = 0f

    // True from a gesture's first finger-driven delta until the list hands the release to its fling.
    private var userDragActive = false

    /**
     * Gesture-direction-based `controlsVisible`: a net upward drag (accumulated `available.y` past
     * `-overlayToggleThresholdPx`) hides the overlay, a net downward drag past
     * `+overlayToggleThresholdPx` shows it again. Threaded down to QueueList's modifier via
     * `Modifier.nestedScroll` in PlayerSkeletonQueueContent, so this fires on real drag deltas
     * regardless of whether the list itself has room to scroll — a scroll-position signal
     * (`isScrollInProgress`/`firstVisibleItemIndex`) fails when the queue is short enough
     * that the list can never actually scroll away from the top, permanently hiding rows
     * underneath the controls overlay with no way to reveal them.
     *
     * The count belongs to one gesture: the first finger-driven ([NestedScrollSource.UserInput])
     * delta after a release starts it from zero. The fling that follows a release
     * ([NestedScrollSource.SideEffect] deltas) still counts toward the gesture it belongs to, but
     * nothing it or an earlier gesture left behind counts toward the next drag.
     *
     * `onPreScroll` (not `onPostScroll`) so this observes the raw drag delta before the child
     * `LazyColumn` consumes any of it — consumption doesn't matter here since this connection
     * never uses the return value to steal scroll, it only observes and always returns
     * `Offset.Zero`.
     */
    val queueScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && !userDragActive) {
                userDragActive = true
                accumulatedScrollDelta = 0f
            }
            accumulatedScrollDelta += available.y
            when {
                accumulatedScrollDelta <= -overlayToggleThresholdPx -> controlsVisible = false
                accumulatedScrollDelta >= overlayToggleThresholdPx -> controlsVisible = true
            }
            return Offset.Zero
        }

        // The finger lifted: the next finger-driven delta starts a new gesture.
        override suspend fun onPreFling(available: Velocity): Velocity {
            userDragActive = false
            return Velocity.Zero
        }
    }

    // Refreshed every recomposition by rememberQueueModeState (a plain field write, not
    // `remember`-ed) so onShuffleButtonClick below always dispatches to the caller's current
    // callback rather than whichever one is live when this object is constructed.
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

    /**
     * The Up Next shuffle button, stable for the same reason as [onQueueClick]: the caller's toggle,
     * and the controls shown. An applied toggle brings the list to its top through the queue
     * generation (see [rememberQueueModeState]); a refused or pending one leaves it where it is.
     */
    val onShuffleButtonClick: () -> Unit = {
        onShuffleClick()
        controlsVisible = true
    }
}

/**
 * Remembers and wires up all queue-mode state for [PlayerSheetSkeleton]: the queue-mode
 * toggle + its crossfade [Animatable], the queue list's [LazyListState], and the
 * gesture-derived controls-overlay visibility.
 *
 * [scope] is the same [CoroutineScope] PlayerSheetSkeleton already holds via
 * `rememberCoroutineScope()` for its own drag-settle/expand animations — reused here rather
 * than creating a second one, so every animation this composable drives shares one scope.
 * [upcomingQueue] and [onShuffleClick] are the raw value/callback PlayerSheetSkeleton receives
 * as its own parameters, threaded through unchanged. [queueGeneration] is
 * [com.v1per.synthx.playback.PlaybackEngine.queueGeneration]: the list scrolls to its top each
 * time it changes, and at no other time.
 */
@Composable
fun rememberQueueModeState(
    scope: CoroutineScope,
    upcomingQueue: List<QueueSlot>,
    queueGeneration: Long,
    onShuffleClick: () -> Unit,
): QueueModeState {
    val density = LocalDensity.current

    // The only piece of queue-mode state that survives a configuration change on its own — see the
    // isQueueModeState comment on QueueModeState above.
    val isQueueModeState = rememberSaveable { mutableStateOf(false) }

    // Starts from the saved flag, so a configuration change in queue mode comes back with queue
    // mode on screen and the queue button's next tap leaving it.
    val queueProgress = remember { Animatable(if (isQueueModeState.value) 1f else 0f) }

    // Hoisted so both QueueList's own scrolling and this composable's derived
    // controlsVisible below observe the same LazyListState.
    val queueListState = rememberLazyListState()

    // Small threshold (tuned to feel, not any existing design constant) a net drag must cross
    // before queueScrollConnection toggles controlsVisible — see that property's doc comment
    // in the QueueModeState class above for the full mechanism.
    val overlayToggleThresholdPx = with(density) { 28.dp.toPx() }

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
    // remembered field: deliberately not rememberSaveable (see isQueueModeState's comment above),
    // but it does survive leaving queue mode, collapsing the sheet, and navigating to a route
    // where PlayerSheetSkeleton is merely zero-sized rather than removed from composition, so a
    // stale false can outlive the interaction that set it and still be waiting on re-entry.
    //
    // Only ever writes `true` — hiding stays entirely gesture-driven, and this branch cannot run
    // while upcomingQueue has items, so a populated queue's scroll-to-hide/show is unaffected.
    LaunchedEffect(upcomingQueue.isEmpty()) {
        if (upcomingQueue.isEmpty()) {
            state.controlsVisible = true
        }
    }

    // The generation the list last scrolled to the top for. It starts at the current value, so
    // neither the first composition nor a composition that comes back with an unchanged generation
    // scrolls. It is saved next to queueListState, which rememberLazyListState saves too: after a
    // configuration change the two come back together, and a generation that moves while the
    // composition is away still brings the restored list to the top.
    val shownGeneration = rememberSaveable { mutableLongStateOf(queueGeneration) }
    LaunchedEffect(queueGeneration) {
        if (queueGeneration == shownGeneration.longValue) return@LaunchedEffect
        // A finger on the list holds its scroll at a priority scrollToItem cannot take over, which
        // would drop the scroll; it runs once the finger and any fling have let go.
        snapshotFlow { queueListState.isScrollInProgress }.first { !it }
        // The engine publishes the new Up Next in the same main-thread step as the generation, so the
        // list composed here already holds the new order and the scroll lands on its first row.
        queueListState.scrollToItem(0)
        shownGeneration.longValue = queueGeneration
    }

    return state
}
