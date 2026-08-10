package com.v1per.synthx.playback

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
        _currentSongId.value = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
        // The current item moved, so "everything after it" moves with it.
        refreshUpcomingQueue()
        refreshNavigationState()
        prefetchArtwork()
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        _repeatMode.value = repeatMode.toRepeatMode()
        persistence.repeatMode = repeatMode
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
            ?: _currentSongId.value?.let { songsById[it]?.title }
        _isBuffering.value = false
        if (title != null) {
            _errors.tryEmit(PlaybackError.PlaybackFailed(title))
        }
    }
}
