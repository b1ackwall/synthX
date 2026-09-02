package com.v1per.synthx.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Builds the [Player.Listener] [ExoPlayerEngine] registers with its `MediaController` —
 * relocated out of the class body purely to keep `ExoPlayerEngine.kt` shorter. Every
 * callback below still reads/writes the engine's own state directly, via the extension
 * receiver captured when this object expression is created.
 */
internal fun ExoPlayerEngine.createPlayerListener(): Player.Listener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        refreshQueue()
        refreshUpcomingQueue()
        refreshNavigationState()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Queue first: PlaybackViewModel resolves the new id against the queue to
        // rebuild currentSong, so the queue must already be up to date when the id lands.
        refreshQueue()
        val currentSlotId = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
        _currentSlotId.value = currentSlotId
        _currentSongId.value = currentSlotId?.let { songsBySlotId[it]?.id }
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
        controller?.let { ctrl ->
            val index = ctrl.currentMediaItemIndex
            if (index != C.INDEX_UNSET) {
                persistence.updateCursor(ctrl.currentMediaItem?.mediaId, ctrl.currentPosition)
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        _repeatMode.value = repeatMode.toRepeatMode()
        persistence.updateRepeatMode(repeatMode)
        prefetchArtwork()
        refreshNavigationState()
    }

    /**
     * STATE_ENDED means the *whole* timeline finished, not that one item ended —
     * media3 auto-advances between queue items without leaving STATE_READY. The
     * hasNextMediaItem() guard is belt-and-braces: it accounts for repeat mode and
     * shuffle order, so anything still reachable keeps playback alive and only a
     * genuinely exhausted queue falls through to stop().
     *
     * Also the only place [_isBuffering] is driven: true exactly while STATE_BUFFERING,
     * false in every other state (including the error path, which never reaches
     * STATE_BUFFERING but may leave a stale true behind if a stall preceded the failure).
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        _isBuffering.value = playbackState == Player.STATE_BUFFERING
        if (playbackState != Player.STATE_ENDED) return
        val ctrl = controller ?: return
        if (!ctrl.hasNextMediaItem()) stop()
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
        _isBuffering.value = false
        if (title != null) {
            _errors.tryEmit(PlaybackError.PlaybackFailed(title))
        }
    }
}
