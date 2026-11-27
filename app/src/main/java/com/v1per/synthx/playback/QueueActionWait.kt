package com.v1per.synthx.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one bound on how long a held Play Next, Add to Queue or Keep waits, counted from the moment it
 * is held and covering the restore and the queue load together. A queue load suspends while it builds
 * the start song's artwork (local reads, well under a second) and while it waits for the session
 * connection, which can take a whole round of attempts ([ReconnectPolicy]: four attempts with 1, 2
 * and 4 s of backoff, at least about 7 s). The bound is about twice that, the same as
 * [QUEUE_RESTORE_WAIT_TIMEOUT_MS]: a slow connection that still succeeds is not cut off, and a session
 * that never answers releases the actions in a bounded time.
 */
internal const val QUEUE_ACTION_WAIT_TIMEOUT_MS = 15_000L

/**
 * Runs the view model's queue loads one at a time: a new load cancels the one before it, and
 * [inFlight] is true from a load's start until the latest load returns, fails or is cancelled. A
 * superseded load ending late never clears it, because a generation number names the latest load.
 *
 * Main-thread-confined: [scope] is the view model's.
 */
internal class QueueLoadTracker(private val scope: CoroutineScope) {
    private val _inFlight = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    private var generation = 0L
    private var job: Job? = null

    /** Starts [load] as the latest queue load, cancelling any earlier one still running. */
    fun launch(load: suspend () -> Unit) {
        job?.cancel()
        val mine = ++generation
        // Set before the launch: an action taken right after this call already sees the load.
        _inFlight.value = true
        job = scope.launch {
            try {
                load()
            } finally {
                if (generation == mine) _inFlight.value = false
            }
        }
    }
}

/**
 * Holds Play Next, Add to Queue and Keep while the saved-queue restore or a queue load is pending, so
 * they never decide on a queue that the pending restore or load is about to swap out, and holds the
 * fresh plays (song taps, page Play and Shuffle) while the restore alone is pending, so they decide
 * with the restored queue's customised flag. Held actions run once each, in holding order, on one
 * coroutine: each waits until nothing it waits for is pending or its own bound ([timeoutMs] from the
 * moment it is held) passes, then runs. An action that starts a load of its own runs to its end
 * first; the next held Play Next, Add to Queue or Keep then waits for that load, and the next held
 * fresh play does not. Nothing is held when nothing is pending.
 *
 * Only the latest fresh play counts: holding one, or deciding one at once, drops every fresh play
 * held before it, wherever it sits in the order.
 *
 * Main-thread-confined: [scope] is the view model's.
 */
internal class QueueActionWait(
    private val scope: CoroutineScope,
    private val restoreState: StateFlow<QueueRestoreState>,
    private val loadInFlight: StateFlow<Boolean>,
    private val timeoutMs: Long,
) {
    private class Held(
        val waitsForRestore: Boolean,
        val waitsForLoad: Boolean,
        val freshPlay: Boolean,
        val action: suspend () -> Unit,
    ) {
        val timedOut = MutableStateFlow(false)
        val dropped = MutableStateFlow(false)
        var bound: Job? = null
    }

    private val held = ArrayDeque<Held>()
    private var releaseJob: Job? = null

    private fun restorePending(): Boolean = restoreState.value == QueueRestoreState.Pending

    private fun pending(waitsForRestore: Boolean): Boolean = loadInFlight.value || (waitsForRestore && restorePending())

    private fun Held.isPending(): Boolean =
        (waitsForLoad && loadInFlight.value) || (waitsForRestore && restorePending())

    /**
     * Holds [action] and returns true while a load is in flight, or while the restore is pending and
     * [waitsForRestore]; returns false, holding nothing, otherwise. The caller decides
     * [waitsForRestore]: an action against active playback never waits for a restore.
     */
    fun holdWhilePending(waitsForRestore: Boolean, action: suspend () -> Unit): Boolean {
        if (!pending(waitsForRestore)) return false
        hold(Held(waitsForRestore, waitsForLoad = true, freshPlay = false, action))
        return true
    }

    /**
     * A fresh play: drops every fresh play held before it, then holds [action] and returns true while
     * the restore is pending and [waitsForRestore]; returns false, holding nothing, otherwise. Never
     * waits for a queue load, so a newer fresh play still cancels the load of an older one.
     */
    fun holdFreshPlayWhileRestoring(waitsForRestore: Boolean, action: suspend () -> Unit): Boolean {
        held.filter { it.freshPlay }.forEach { earlier ->
            earlier.dropped.value = true
            earlier.bound?.cancel()
        }
        if (!waitsForRestore || !restorePending()) return false
        hold(Held(waitsForRestore = true, waitsForLoad = false, freshPlay = true, action))
        return true
    }

    private fun hold(entry: Held) {
        entry.bound = scope.launch {
            delay(timeoutMs)
            entry.timedOut.value = true
        }
        held.addLast(entry)
        if (releaseJob?.isActive != true) releaseJob = scope.launch { release() }
    }

    private suspend fun release() {
        while (held.isNotEmpty()) {
            val entry = held.first()
            combine(restoreState, loadInFlight, entry.timedOut, entry.dropped) { _, _, timedOut, dropped ->
                dropped || timedOut || !entry.isPending()
            }.first { it }
            held.removeFirst()
            entry.bound?.cancel()
            if (!entry.dropped.value) entry.action()
        }
    }
}
