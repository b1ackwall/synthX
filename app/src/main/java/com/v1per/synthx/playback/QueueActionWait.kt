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
 * they never decide on a queue that the pending restore or load is about to swap out. Held actions
 * run once each, in holding order, on one coroutine: each waits until nothing it waits for is
 * pending or its own bound ([timeoutMs] from the moment it is held) passes, then runs. An action
 * that starts a load of its own runs to its end first; the next held action then waits for that
 * load. Nothing is held when nothing is pending.
 *
 * Main-thread-confined: [scope] is the view model's.
 */
internal class QueueActionWait(
    private val scope: CoroutineScope,
    private val restoreState: StateFlow<QueueRestoreState>,
    private val loadInFlight: StateFlow<Boolean>,
    private val timeoutMs: Long,
) {
    private class Held(val waitsForRestore: Boolean, val action: suspend () -> Unit) {
        val timedOut = MutableStateFlow(false)
        var bound: Job? = null
    }

    private val held = ArrayDeque<Held>()
    private var releaseJob: Job? = null

    private fun pending(waitsForRestore: Boolean): Boolean =
        loadInFlight.value || (waitsForRestore && restoreState.value == QueueRestoreState.Pending)

    /**
     * Holds [action] and returns true while a load is in flight, or while the restore is pending and
     * [waitsForRestore]; returns false, holding nothing, otherwise. The caller decides
     * [waitsForRestore]: an action against active playback never waits for a restore.
     */
    fun holdWhilePending(waitsForRestore: Boolean, action: suspend () -> Unit): Boolean {
        if (!pending(waitsForRestore)) return false
        val entry = Held(waitsForRestore, action)
        entry.bound = scope.launch {
            delay(timeoutMs)
            entry.timedOut.value = true
        }
        held.addLast(entry)
        if (releaseJob?.isActive != true) releaseJob = scope.launch { release() }
        return true
    }

    private suspend fun release() {
        while (held.isNotEmpty()) {
            val entry = held.first()
            combine(restoreState, loadInFlight, entry.timedOut) { _, _, timedOut ->
                timedOut || !pending(entry.waitsForRestore)
            }.first { it }
            held.removeFirst()
            entry.bound?.cancel()
            entry.action()
        }
    }
}
