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
     */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        val ctrl = controller ?: return
        if (!ctrl.hasNextMediaItem()) stop()
    }

    // A failed prepare lands in STATE_IDLE, which onPlaybackStateChanged above
    // deliberately ignores — without this the mini player would sit on a song that
    // never plays. stop() clears currentSongId, which cascades to currentSong.
    override fun onPlayerError(error: PlaybackException) {
        // Read the failing item's title before stop(), while the controller is still
        // positioned on it.
        val title = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: _currentSongId.value?.let { songsById[it]?.title }
        stop()
        if (title != null) {
            _errors.tryEmit(PlaybackError.PlaybackFailed(title))
        }
    }
}
