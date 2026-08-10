package com.v1per.synthx.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import com.v1per.synthx.data.repository.Song

/**
 * The single hook every structural change to the timeline runs through: re-derives the
 * queue flows, writes the new structure to storage, and records whether the queue is
 * still a pristine context list or has been hand-modified.
 *
 * A future reorder or remove only has to call this — with [fresh] false, since anything
 * that is not a wholesale `playQueue` is by definition the user customising the queue.
 */
internal fun ExoPlayerEngine.onQueueStructureChanged(fresh: Boolean) {
    refreshQueue()
    refreshUpcomingQueue()
    persistQueue()
    _isQueueCustomized.value = !fresh
    prefetchArtwork()
}

internal fun ExoPlayerEngine.persistQueue() {
    persistence.saveQueue(_queue.value)
}

/**
 * media3's own next/previous navigation treats Repeat One as Repeat Off — it ignores
 * the single-item loop and looks for a genuinely different next window — so
 * [MediaController.hasNextMediaItem] alone would disable the next button at the last
 * item even while repeating it. The `|| repeatMode == One` override matches the
 * product requirement instead: a track the user is repeating is still one they expect
 * to be able to skip off of.
 */
internal fun ExoPlayerEngine.refreshNavigationState() {
    val ctrl = controller
    _hasNext.value = ctrl != null && (ctrl.hasNextMediaItem() || _repeatMode.value == RepeatMode.One)
}

/**
 * Rebuilds the engine's `queue`/`currentIndex` from the controller's timeline. The
 * timeline is the single source of truth — every mutation (setMediaItems, addMediaItem,
 * move/remove) lands there, so reading it back avoids keeping a parallel list in sync.
 */
internal fun ExoPlayerEngine.refreshQueue() {
    val ctrl = controller
    if (ctrl == null) {
        _queue.value = emptyList()
        _currentIndex.value = -1
        return
    }

    val timeline = ctrl.currentTimeline
    val window = Timeline.Window()
    val songs = ArrayList<Song>(timeline.windowCount)
    for (i in 0 until timeline.windowCount) {
        val mediaId = timeline.getWindow(i, window).mediaItem.mediaId
        // Only this engine ever populates the timeline, so the cache always has the
        // Song; an unknown id (e.g. an item restored by the service from a previous
        // process) is skipped rather than faked.
        songsById[mediaId]?.let { songs.add(it) }
    }

    _queue.value = songs
    _currentIndex.value = ctrl.currentMediaItemIndex.takeIf {
        it != C.INDEX_UNSET && it < songs.size
    } ?: -1
}

/**
 * Rebuilds the engine's `upcomingQueue` (and the parallel `upcomingTimelineIndices`
 * display-index -> real-timeline-index map) by walking forward from the current item one
 * window at a time via [Timeline.getNextWindowIndex] with [Player.REPEAT_MODE_OFF] forced
 * — this is a single forward pass for display purposes, not the player's actual repeat
 * setting, so it terminates at the real end of the queue instead of looping. The shuffle
 * param is a literal `false`, not `ctrl.shuffleModeEnabled`: media3's native shuffle is no
 * longer used at all (ordering is entirely app-managed — see [QueueBaselineManager]), so
 * `shuffleModeEnabled` always reads `false` regardless, and the timeline itself is already
 * in whatever order `setMediaItems`/`moveMediaItem` last wrote it in — a plain forward walk
 * over it is exactly the shuffled-or-not order that is actually live.
 *
 * Guarded against an empty/unready timeline: [MediaController.getCurrentMediaItemIndex]
 * returns `0`, not [C.INDEX_UNSET], when the timeline has no windows yet, so that check
 * alone doesn't catch a listener callback (e.g. `onTimelineChanged`) firing before
 * `setMediaItems` has fully landed. Bounds-checking against the timeline's actual window
 * count closes that off regardless of which caller manages to invoke this too early.
 */
internal fun ExoPlayerEngine.refreshUpcomingQueue() {
    val ctrl = controller
    val startIndex = ctrl?.currentMediaItemIndex
    val timeline = ctrl?.currentTimeline
    if (ctrl == null || timeline == null || startIndex == null ||
        startIndex == C.INDEX_UNSET || startIndex !in 0 until timeline.windowCount
    ) {
        _upcomingQueue.value = emptyList()
        upcomingTimelineIndices = emptyList()
        return
    }

    val window = Timeline.Window()
    val songs = ArrayList<Song>()
    val indices = ArrayList<Int>()

    // visited guards against an unforeseen cycle; REPEAT_MODE_OFF should already
    // terminate at C.INDEX_UNSET on its own, this is defensive insurance only.
    val visited = HashSet<Int>()
    var index = timeline.getNextWindowIndex(startIndex, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false)
    while (index != C.INDEX_UNSET && visited.add(index)) {
        val mediaId = timeline.getWindow(index, window).mediaItem.mediaId
        songsById[mediaId]?.let {
            songs.add(it)
            indices.add(index)
        }
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled= */ false)
    }

    _upcomingQueue.value = songs
    upcomingTimelineIndices = indices
}

internal fun ExoPlayerEngine.currentSong(): Song? = _currentSongId.value?.let { songsById[it] }

/**
 * Stateless — takes the controller explicitly rather than as an [ExoPlayerEngine]
 * extension receiver, so [applyReorderViaMoves] (a top-level function with no
 * [ExoPlayerEngine] instance available at all) can call it too.
 */
internal fun indexOfMediaId(ctrl: MediaController, mediaId: String): Int? {
    for (i in 0 until ctrl.mediaItemCount) {
        if (ctrl.getMediaItemAt(i).mediaId == mediaId) return i
    }
    return null
}
