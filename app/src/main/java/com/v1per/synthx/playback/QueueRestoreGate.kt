package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How long Play Next, Add to Queue and the Keep fallback wait for a pending saved-queue restore
 * before they fall back to playing the song alone. The restore can itself wait out a whole round
 * of session connection attempts ([ReconnectPolicy]: four attempts with 1, 2 and 4 s of backoff
 * between them, at least about 7 s), so the bound is about twice that backoff: a slow connection
 * that still succeeds is not cut off, and a session that never answers releases the actions in a
 * bounded time.
 */
internal const val QUEUE_RESTORE_WAIT_TIMEOUT_MS = 15_000L

/** Where the launch-time saved-queue restore stands, as [PlaybackViewModel] tracks it. */
enum class QueueRestoreState {
    /** No restore is requested: nothing waits. */
    NotRequested,

    /** A restore is running and its wait bound has not passed: the waiting actions are held. */
    Pending,

    /** The restore returned (installed, skipped, nothing saved, failed) or its wait bound passed. */
    Done,
}

/**
 * Holds actions that must not decide "nothing is playing" while a restore is still running. Every
 * held action runs once, in holding order, as soon as the restore returns or [timeoutMs]
 * passes, whichever is first. The bound runs on the scope's own clock, so tests drive it with
 * virtual time. A restore that returns after the bound runs no held action again; the engine itself
 * refuses to install a saved queue over a timeline that exists by then.
 *
 * Main-thread-confined: [scope] is the view model's.
 */
internal class QueueRestoreGate(private val scope: CoroutineScope, private val timeoutMs: Long) {
    private val _state = MutableStateFlow(QueueRestoreState.NotRequested)
    val state: StateFlow<QueueRestoreState> = _state.asStateFlow()

    private var restoreJob: Job? = null
    private val held = ArrayDeque<suspend () -> Unit>()
    private var releaseJob: Job? = null

    /** Runs [restore] as the pending restore, unless one is still running. */
    fun start(restore: suspend () -> Unit) {
        if (restoreJob?.isActive == true) return
        _state.value = QueueRestoreState.Pending
        val bound = scope.launch {
            delay(timeoutMs)
            _state.value = QueueRestoreState.Done
        }
        restoreJob = scope.launch {
            try {
                restore()
            } finally {
                bound.cancel()
                _state.value = QueueRestoreState.Done
            }
        }
    }

    /** Holds [action] until the restore settles and returns true, or returns false when none is pending. */
    fun holdWhilePending(action: suspend () -> Unit): Boolean {
        if (_state.value != QueueRestoreState.Pending) return false
        held.addLast(action)
        if (releaseJob?.isActive != true) {
            releaseJob = scope.launch {
                state.first { it != QueueRestoreState.Pending }
                while (held.isNotEmpty()) held.removeFirst().invoke()
            }
        }
        return true
    }
}

/** Active playback, read from the engine itself rather than from a view model copy. */
private suspend fun PlaybackController.hasActivePlaybackNow(): Boolean =
    currentSongId.first() != null && queue.first().isNotEmpty() && !isQueueEnded.first()

/**
 * Play Next after a restore settled: false when no queue came back. A song already right after the
 * current one is not inserted again, unless [force]: the confirmation that would ask has no dialog
 * to show it in, so the insert is skipped.
 */
internal suspend fun PlaybackController.playNextAfterRestore(song: Song, force: Boolean): Boolean {
    if (!hasActivePlaybackNow()) return false
    if (force || upcomingQueue.first().firstOrNull()?.song?.id != song.id) playNext(song)
    return true
}

/** Add to Queue after a restore settled, with the same skip for a song already last in Up Next. */
internal suspend fun PlaybackController.addToQueueAfterRestore(song: Song, force: Boolean): Boolean {
    if (!hasActivePlaybackNow()) return false
    if (force || upcomingQueue.first().lastOrNull()?.song?.id != song.id) addToQueue(song)
    return true
}

/** Keep after a restore settled: the song is played next and jumped to, unless it is already queued. */
internal suspend fun PlaybackController.keepAfterRestore(song: Song): Boolean {
    if (!hasActivePlaybackNow()) return false
    val queued = currentSongId.first() == song.id || upcomingQueue.first().any { it.song.id == song.id }
    if (!queued && playNext(song)) next()
    return true
}
