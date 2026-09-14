package com.v1per.synthx.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/** How often [periodicCheckpointDriver] ticks while playback is running (P4-T7). */
internal const val PERIODIC_CHECKPOINT_INTERVAL_MS = 30_000L

/**
 * Builds the [Player.Listener] [ExoPlayerEngine] registers with its `MediaController` —
 * relocated out of the class body purely to keep `ExoPlayerEngine.kt` shorter. Every callback
 * below still reads/writes the engine's own state directly, via the extension receiver captured
 * when this object expression is created.
 */
internal fun ExoPlayerEngine.createPlayerListener(): Player.Listener = object : Player.Listener {
    /**
     * P4-T7 / R3-F13: the only observable signal for "playback stopped" that exists at all —
     * see this file's header note on why. No app-level `AudioManager` focus listener or
     * becoming-noisy receiver exists anywhere in this codebase; both are delegated entirely to
     * Media3 (`handleAudioFocus = true` / `setHandleAudioBecomingNoisy(true)` in
     * `SynthXPlaybackService.onCreate`), and the only thing either produces on the app side is
     * this same callback flipping to `false`. Checkpointing here is deliberately a "catch the
     * symptom, not the cause" fix: it cannot distinguish a user's own pause() (already
     * checkpointed there, so this is a harmless redundant write) from a focus loss or
     * becoming-noisy pause (previously never checkpointed at all) — but with no finer-grained
     * hook available, catching every stop is the only way to close the gap.
     */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
        handleIsPlayingChangedForCheckpoint(isPlaying) { checkpointCursor() }
    }

    /**
     * No longer a queue signal. The timeline is a bounded window into the order store rather
     * than the queue itself, so its changes are almost always the *result* of edits
     * [maintainWindow] just applied — which is also why window maintenance must not be
     * triggered from here, as that would recurse. Navigation state is refreshed defensively;
     * it is derived from the order store, so this is a cheap no-op in the normal case.
     */
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refreshNavigationState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val currentSlotId = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
        publishCurrentSlot(currentSlotId)
        // Only the cursor moved, not the order, so the full `queue` projection is left alone
        // and just its index is re-derived. The resolved current Song was published directly
        // above from the engine's cache, independently of queue-flow emission timing.
        refreshCurrentIndex()
        // The primary window-refill trigger. Unforced, so the hysteresis in windowNeedsRefill
        // applies and the steady state is one small add/remove pair every ~20 tracks rather
        // than a timeline edit on every single one.
        maintainWindow()
        // The current item moved, so "everything after it" moves with it.
        refreshUpcomingQueue()
        refreshNavigationState()
        prefetchArtwork()
        // Lightweight cursor-only persistence for the track-transition case (data 2.2):
        // pause/onStop/onTaskRemoved are the only *full* writes, and none of them repeat
        // during extended, uninterrupted background/notification-controlled playback — so
        // without this, the durable resume point can go arbitrarily stale as playback
        // auto-advances through many tracks unattended. Fires in-app regardless of whether
        // the Activity is foregrounded: the MediaController mirrors session state from the
        // service, so this callback still lands even when the app is backgrounded. Reads
        // currentPosition rather than hardcoding 0 — media3 already reports the position
        // within the new window at the moment this fires, so this is exact rather than
        // assuming a fresh track always starts at zero (e.g. Repeat One's own seek).
        checkpointCursor()
    }

    /**
     * P4-T7 / R3-F13: a same-item seek (the user drags the seek bar, or taps forward/back
     * within the currently playing track) previously never triggered a checkpoint at all —
     * [onMediaItemTransition] only fires when the item itself changes, not on a seek within
     * one. Only [Player.DISCONTINUITY_REASON_SEEK] is checkpointed here: an automatic
     * discontinuity (e.g. the internal adjustment media3 sometimes reports around a transition)
     * is already covered by [onMediaItemTransition] and would just be a redundant write.
     * Restricted to a same-item seek specifically because a cross-item seek already gets a
     * fresh, correct checkpoint from [onMediaItemTransition] a moment later — checkpointing
     * here too would just race it with the stale pre-seek media item.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        handleSameItemSeekForCheckpoint(oldPosition.mediaItemIndex, newPosition.mediaItemIndex, reason) {
            val slotId = newPosition.mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            persistence.updateCursor(slotId, newPosition.positionMs)
        }
    }

    /**
     * Forces a window rebuild: Repeat All is what decides whether the window wraps around the
     * true end of the queue (see [computeWindow]), so toggling it changes which slots should be
     * loaded, not merely how much lookahead is left. [refreshNavigationState] follows for the
     * same reason — `hasNext` is repeat-aware.
     */
    override fun onRepeatModeChanged(repeatMode: Int) {
        _repeatMode.value = repeatMode.toRepeatMode()
        persistence.updateRepeatMode(repeatMode)
        maintainWindow(force = true)
        prefetchArtwork()
        refreshNavigationState()
    }

    /**
     * STATE_ENDED means the *loaded timeline* finished, which is no longer the same thing as
     * the queue finishing: the timeline only holds a window, so running off its end can happen
     * with hundreds of songs still queued. [advancePastWindowEnd] consults the order store and
     * keeps playing from the true next slot; only a genuinely exhausted queue returns false and
     * falls through to stop().
     *
     * Hysteresis in [maintainWindow] should keep this from firing mid-queue at all, but a
     * silent stop partway through a 2000-song queue is not a failure worth leaving to "should".
     *
     * Also the only place [_isBuffering] is driven: true exactly while STATE_BUFFERING, false
     * in every other state (including the error path, which never reaches STATE_BUFFERING but
     * may leave a stale true behind if a stall preceded the failure).
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        _isBuffering.value = playbackState == Player.STATE_BUFFERING
        if (playbackState != Player.STATE_ENDED) return
        if (advancePastWindowEnd()) return
        stop()
    }

    /**
     * Deliberately does *not* call stop(): stop() clears currentSongId, which used to make
     * the entire mini-player/expanded-player UI vanish the instant one track failed (corrupt
     * file, missing file) — the user would see the error Snackbar flash by and then the
     * player chrome would be gone, with no way to skip past the failure. isPlaying already
     * flips to false on its own (media3 emits onIsPlayingChanged alongside the error), so the
     * player renders as "paused on the song that failed" instead — the existing skip/previous
     * controls stay live so the user can move on without the chrome disappearing first.
     */
    override fun onPlayerError(error: PlaybackException) {
        val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: _currentSlotId.value?.let { songsBySlotId[it]?.title }
        // Was previously received and discarded — the code/cause is what actually distinguishes
        // a missing file from a revoked content provider from a decoder failure, none of which
        // the user-facing snackbar below carries.
        Log.e(ExoPlayerEngine.TAG, playerErrorLogMessage(error, _currentSlotId.value, title), error)
        _isBuffering.value = false
        if (title != null) {
            _errors.tryEmit(PlaybackError.PlaybackFailed(title))
        }
    }
}

/**
 * Split out of [onPlayerError] purely so its content is unit-testable without a real
 * `MediaController`/`Player.Listener` — [error]'s code/cause is the whole point of this log line
 * (see [onPlayerError]'s kdoc for why the snackbar it feeds can't carry the same detail).
 */
internal fun playerErrorLogMessage(error: PlaybackException, slotId: String?, title: String?): String =
    "Player error: code=${error.errorCode} (${error.errorCodeName}), slot=$slotId, title=$title"

/**
 * The pure decision behind [onIsPlayingChanged]'s checkpoint, split out for plain-JVM testing —
 * neither this predicate nor [checkpoint] itself needs a real `MediaController`, unlike the
 * actual [checkpointCursor] the production listener passes in. See [onIsPlayingChanged]'s kdoc
 * for why "any stop" is the deliberately broad condition here.
 */
internal fun handleIsPlayingChangedForCheckpoint(isPlaying: Boolean, checkpoint: () -> Unit) {
    if (!isPlaying) checkpoint()
}

/**
 * The pure decision behind [onPositionDiscontinuity]'s checkpoint, split out for the same
 * plain-JVM testing reason as [handleIsPlayingChangedForCheckpoint] — see that function's kdoc.
 */
internal fun handleSameItemSeekForCheckpoint(
    oldMediaItemIndex: Int,
    newMediaItemIndex: Int,
    reason: Int,
    checkpoint: () -> Unit,
) {
    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
    if (oldMediaItemIndex != newMediaItemIndex) return
    checkpoint()
}

/**
 * Shared cursor-only checkpoint (P4-T7), used from every listener callback that observes
 * playback having moved or stopped without necessarily changing the queue structure —
 * [onMediaItemTransition]'s track-transition case, [onIsPlayingChanged]'s stop case, and
 * [onPositionDiscontinuity]'s same-item-seek case. Deliberately goes through
 * [QueuePersistence.updateCursor], never the unified writer or [persistQueue] — those exist for
 * multi-field structural writes (a fresh play, a shuffle toggle) or full-order rewrites, neither
 * of which applies here: only the resume position moved.
 */
internal fun ExoPlayerEngine.checkpointCursor() {
    val ctrl = controller ?: return
    if (ctrl.currentMediaItemIndex == C.INDEX_UNSET) return
    persistence.updateCursor(ctrl.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }, ctrl.currentPosition)
}

/**
 * P4-T7 / R3-F13: the periodic-checkpoint half of the fix — long, uninterrupted playback of a
 * single long track previously went un-checkpointed between the track's start and whatever
 * later event (its own end, a pause, backgrounding) eventually saved a position. Emits one tick
 * every [intervalMs] for as long as [isPlaying] reports `true`, and stops ticking the instant it
 * flips `false` — [transformLatest] cancels the running `while(true)` delay loop below exactly
 * like [kotlinx.coroutines.flow.collectLatest] cancels a stale collector, so a paused/stopped
 * player is never left with a scheduled tick still pending.
 *
 * Deliberately a standalone [Flow] rather than inline engine code, mirroring
 * [ExoPlayerEngine]'s own `positionTicker` flow+delay convention (see its kdoc) instead of a
 * `Handler`/`postDelayed` loop — and, unlike a method on the engine, a pure function of an
 * injected `isPlaying` [Flow], which is what makes it testable at the plain-JVM level with a
 * fake `MutableStateFlow<Boolean>` and virtual time, without any real `MediaController`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun periodicCheckpointDriver(
    isPlaying: Flow<Boolean>,
    intervalMs: Long = PERIODIC_CHECKPOINT_INTERVAL_MS,
): Flow<Unit> = isPlaying.transformLatest { playing ->
    if (!playing) return@transformLatest
    while (true) {
        delay(intervalMs)
        emit(Unit)
    }
}
