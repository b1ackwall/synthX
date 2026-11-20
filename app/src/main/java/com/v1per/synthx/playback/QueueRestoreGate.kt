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
 * How long a saved-queue restore counts as pending at most: past it, Play Next, Add to Queue and Keep
 * stop waiting for it and decide on what is playing. The restore can itself wait out a whole round
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
 * Tracks the launch-time saved-queue restore: [state] is Pending from [start] until the restore
 * returns or [timeoutMs] passes, whichever is first. The bound runs on the scope's own clock, so
 * tests drive it with virtual time. Actions that wait for the restore are held by [QueueActionWait];
 * a restore that returns after the bound releases nothing again, and the engine itself refuses to
 * install a saved queue over a timeline that exists by then.
 *
 * Main-thread-confined: [scope] is the view model's.
 */
internal class QueueRestoreGate(private val scope: CoroutineScope, private val timeoutMs: Long) {
    private val _state = MutableStateFlow(QueueRestoreState.NotRequested)
    val state: StateFlow<QueueRestoreState> = _state.asStateFlow()

    private var restoreJob: Job? = null

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
}

/** Active playback, read from the engine itself rather than from a view model copy. */
private suspend fun PlaybackController.hasActivePlaybackNow(): Boolean =
    currentSongId.first() != null && queue.first().isNotEmpty() && !isQueueEnded.first()

/** True when [song] sits in the first Up Next slot, read from the engine itself. */
private suspend fun PlaybackController.isExactlyNextNow(song: Song): Boolean =
    upcomingQueue.first().firstOrNull()?.song?.id == song.id

/**
 * Play Next after a wait settled: false when no queue came back. A song already right after the
 * current one is not inserted again, unless [force]: the confirmation that would ask has no dialog
 * to show it in, so the insert is skipped.
 */
internal suspend fun PlaybackController.playNextAfterWait(song: Song, force: Boolean): Boolean {
    if (!hasActivePlaybackNow()) return false
    if (force || !isExactlyNextNow(song)) playNext(song)
    return true
}

/** Add to Queue after a wait settled, with the same skip for a song already last in Up Next. */
internal suspend fun PlaybackController.addToQueueAfterWait(song: Song, force: Boolean): Boolean {
    if (!hasActivePlaybackNow()) return false
    if (force || upcomingQueue.first().lastOrNull()?.song?.id != song.id) addToQueue(song)
    return true
}

/**
 * Keep after a wait settled, by the rules of [QueuePromptCoordinator.keepQueueAndPlayNext]: false
 * when no queue came back. A refused insert (no session connection) has already been reported on the
 * errors flow, so it neither moves nor plays.
 */
internal suspend fun PlaybackController.keepAfterWait(song: Song, resume: () -> Unit): Boolean {
    if (!hasActivePlaybackNow()) return false
    if (isExactlyNextNow(song) || playNext(song)) {
        next()
        resume()
    }
    return true
}
