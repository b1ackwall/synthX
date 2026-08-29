package com.v1per.synthx.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.session.MediaController
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Above this queue size, [applyReorderViaMoves]'s one-`moveMediaItem`-per-swap rebuild
 * generates enough allocation pressure in media3's internal timeline rebuild (seen OOMing
 * the playback thread on a ~1500-song queue) that [applyReorderViaSetMediaItems] is used
 * instead. Below it, the move-based rebuild is cheap and keeps its no-interruption behaviour.
 */
private const val SHUFFLE_LARGE_QUEUE_THRESHOLD = 300

/**
 * Real bodies for [ExoPlayerEngine]'s [PlaybackEngine] queue-mutation overrides — the class
 * itself keeps only one-line delegators (`override fun playQueue(...) = playQueueInternal(...)`)
 * because Kotlin requires `override` members to be declared inside the class body; these
 * `xxxInternal` extension functions are where the actual logic lives, relocated here purely
 * to keep `ExoPlayerEngine.kt` shorter. Baseline-touching fragments now delegate to
 * [QueueBaselineManager] via [ExoPlayerEngine.baselineManager] instead of mutating a field
 * directly — same logic, same persistence calls, just relocated behind that class's API.
 */

internal suspend fun ExoPlayerEngine.playQueueInternal(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    val start = startIndex.coerceIn(0, songs.lastIndex)

    // Only the starting item gets its artwork up front — building bytes for every
    // song in the queue would mean decoding/generating hundreds of bitmaps before a
    // single note plays. The rest are filled in by prefetchArtwork() as they approach.
    val startArtwork = buildArtworkData(songs[start])

    // Building a MediaItem per song is pure CPU work (Uri.parse + builder calls, no I/O) —
    // dispatched off Main so a large queue doesn't block the UI thread just to construct
    // the list setMediaItems below will consume.
    val mediaItems = withContext(Dispatchers.Default) {
        songs.mapIndexed { index, song ->
            buildMediaItem(song, if (index == start) startArtwork else null)
        }
    }

    // MediaController is main-thread-only and the hops above may have moved us off it
    withContext(Dispatchers.Main.immediate) {
        val ctrl = awaitController() ?: return@withContext
        songsById.clear()
        songs.forEach { songsById[it.id] = it }
        artworkLoadedIds.clear()
        artworkLoadedIds.add(songs[start].id)

        ctrl.setMediaItems(mediaItems, start, C.TIME_UNSET)
        ctrl.prepare()
        ctrl.play()

        // A fresh queue always fully replaces the baseline — never merges with
        // whatever baseline existed before.
        baselineManager.captureFresh(songs)

        onQueueStructureChanged(fresh = true)
    }
}

/**
 * Restores without playing: `MediaController.prepare` buffers to STATE_READY but
 * playWhenReady stays false, so the mini player comes up loaded and paused (Apple
 * Music's behaviour) and the user starts it.
 */
internal suspend fun ExoPlayerEngine.restoreSavedQueueInternal() {
    val restored = persistence.loadQueue() ?: return
    val startSong = restored.songs[restored.startIndex]
    val startArtwork = buildArtworkData(startSong)
    // Loaded alongside the active queue rather than derived from it — baseline can
    // legitimately differ from the active list (e.g. history dropped by an ON->OFF
    // toggle last session), so it needs its own read, not a recomputation.
    val restoredBaseline = persistence.loadBaselineQueue()

    val mediaItems = withContext(Dispatchers.Default) {
        restored.songs.map { song ->
            buildMediaItem(song, if (song.id == startSong.id) startArtwork else null)
        }
    }

    withContext(Dispatchers.Main.immediate) {
        val ctrl = awaitController() ?: return@withContext
        // Not restored over an in-progress session: the user has already started
        // something, and replacing it with last session's queue would be a surprise.
        if (ctrl.mediaItemCount > 0) return@withContext

        songsById.clear()
        restored.songs.forEach { songsById[it.id] = it }
        restoredBaseline.forEach { songsById.putIfAbsent(it.id, it) }
        artworkLoadedIds.clear()
        artworkLoadedIds.add(startSong.id)

        ctrl.setMediaItems(mediaItems, restored.startIndex, restored.positionMs)
        ctrl.repeatMode = persistence.repeatMode
        ctrl.prepare()

        baselineManager.restore(restoredBaseline)
        // Plain field set — no media3 involvement, no rebuild. The active queue just
        // loaded from queue_items already reflects whatever order (shuffled or not)
        // was live when the app last closed, so there is nothing to reconstruct.
        _shuffleEnabled.value = persistence.shuffleEnabled

        // Not routed through onQueueStructureChanged: the persist below is conditional,
        // and a restored queue counts as pristine — the customisation flag is session
        // state that deliberately does not survive the restart.
        refreshQueue()
        refreshUpcomingQueue()
        _isQueueCustomized.value = false
        // The stored rows disagree with what actually loaded — rewrite them so the
        // saved index and the queue cannot drift further apart on the next restore.
        if (restored.droppedCount > 0) {
            Log.i(
                ExoPlayerEngine.TAG,
                "Restored ${restored.songs.size} queue items, " +
                    "${restored.droppedCount} no longer available",
            )
            persistQueue()
            persistence.savePlaybackState(restored.startIndex, restored.positionMs)
        }
        prefetchArtwork()
    }
}

/**
 * Rebuilds the live timeline into shuffled or unshuffled order via [applyReorderViaMoves]
 * — never `setMediaItems` (see that function's kdoc for why). Shuffle ON: the current song
 * stays first, everything else in baseline (played or not) is reshuffled after it. Shuffle
 * OFF: the current song stays first, followed by whatever comes after it in baseline, plus
 * any songs added via [playNextInternal]/[addToQueueInternal] during the shuffle-ON session
 * just ending. Either way, the result becomes the new live order; the OFF branch also
 * becomes the new baseline (see [QueueBaselineManager.computeShuffleTarget]/
 * [QueueBaselineManager.computeUnshuffleTarget]).
 */
internal suspend fun ExoPlayerEngine.setShuffleEnabledInternal(enabled: Boolean) {
    if (enabled == _shuffleEnabled.value) return
    val ctrl = controller ?: return
    val current = currentSong() ?: return

    val target = if (enabled) {
        baselineManager.computeShuffleTarget(current)
    } else {
        baselineManager.computeUnshuffleTarget(current, _queue.value)
    }

    // Shuffle OFF only: computeUnshuffleTarget permanently drops baseline history
    // before the current song, and applyReorderViaMoves/applyReorderViaSetMediaItems
    // below remove those items from the live timeline too — target is exactly what
    // survives, so anything else in songsById is now stale. Shuffle ON's target is a
    // superset of what's already tracked, so nothing needs pruning there.
    if (!enabled) {
        songsById.keys.retainAll(target.mapTo(HashSet()) { it.id })
    }

    if (target.size > SHUFFLE_LARGE_QUEUE_THRESHOLD) {
        applyReorderViaSetMediaItems(ctrl, target, current)
    } else {
        applyReorderViaMoves(ctrl, target)
    }

    _shuffleEnabled.value = enabled
    persistence.shuffleEnabled = enabled
    onQueueReordered()
}

/**
 * Large-queue counterpart to [applyReorderViaMoves]: replaces the whole timeline in one
 * `setMediaItems` call instead of hundreds/thousands of individual `moveMediaItem` calls.
 * This does briefly interrupt/rebuffer the currently-playing item — the exact cost
 * `applyReorderViaMoves` exists to avoid — but capturing [current]'s position and re-seeking
 * to its new index trades that brief interruption for avoiding the OOM the move-based
 * rebuild causes at this scale. Artwork is intentionally left out of the rebuilt
 * [MediaItem]s (unlike [ExoPlayerEngine.playQueueInternal]'s synchronous start-item fetch);
 * clearing [ExoPlayerEngine.artworkLoadedIds] means the caller's [onQueueStructureChanged]
 * -> `prefetchArtwork()` picks it back up asynchronously. The MediaItem build itself runs on
 * [Dispatchers.Default] — this is the branch this codepath exists for (queues large enough
 * to OOM the move-based rebuild), so it's also large enough for bulk MediaItem construction
 * to be worth moving off Main; only the final `setMediaItems`/`prepare` call needs Main.
 */
internal suspend fun ExoPlayerEngine.applyReorderViaSetMediaItems(
    ctrl: MediaController,
    target: List<Song>,
    current: Song,
) {
    val positionMs = ctrl.currentPosition
    val startIndex = target.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val mediaItems = withContext(Dispatchers.Default) {
        target.map { song -> buildMediaItem(song, artworkData = null) }
    }

    withContext(Dispatchers.Main.immediate) {
        artworkLoadedIds.clear()
        ctrl.setMediaItems(mediaItems, startIndex, positionMs)
        ctrl.prepare()
    }
}

/**
 * While shuffle is off, [song] is also woven into baseline at the matching relative
 * slot — permanently, per the locked spec (see [QueueBaselineManager.weaveNext]). While
 * shuffle is on, baseline is left untouched; if shuffle later turns off, [song] is folded
 * into the new baseline then (see [setShuffleEnabledInternal]), not here.
 */
internal fun ExoPlayerEngine.playNextInternal(song: Song) {
    val ctrl = controller ?: return
    songsById[song.id] = song
    val insertAt = (ctrl.currentMediaItemIndex + 1).coerceIn(0, ctrl.mediaItemCount)
    ctrl.addMediaItem(insertAt, buildMediaItem(song, artworkData = null))

    if (!_shuffleEnabled.value) {
        baselineManager.weaveNext(song, currentSong()?.id)
    }

    onQueueStructureChanged(fresh = false)
}

/** Same baseline-weaving rule as [playNextInternal], appended at the end instead of next-slot. */
internal fun ExoPlayerEngine.addToQueueInternal(song: Song) {
    val ctrl = controller ?: return
    songsById[song.id] = song
    ctrl.addMediaItem(buildMediaItem(song, artworkData = null))

    if (!_shuffleEnabled.value) {
        baselineManager.weaveEnd(song)
    }

    onQueueStructureChanged(fresh = false)
}

/**
 * [fromIndex]/[toIndex] are positions in `upcomingQueue`, translated here to real
 * timeline indices via `upcomingTimelineIndices` before touching the controller.
 *
 * While shuffle is off, the same move is mirrored into baseline via
 * [QueueBaselineManager.mirrorMove]. While shuffle is on, this only touches the live
 * timeline; the reorder evaporates the moment shuffle turns off, per spec.
 */
internal fun ExoPlayerEngine.moveQueueItemInternal(fromIndex: Int, toIndex: Int) {
    val ctrl = controller ?: return
    val realFrom = upcomingTimelineIndices.getOrNull(fromIndex) ?: return
    val realTo = upcomingTimelineIndices.getOrNull(toIndex) ?: return
    ctrl.moveMediaItem(realFrom, realTo)

    if (!_shuffleEnabled.value) {
        baselineManager.mirrorMove(fromIndex, toIndex, currentSong()?.id)
    }

    onQueueStructureChanged(fresh = false)
}

/**
 * Removes the item identified by [songId] — no position is accepted from the caller. The
 * display index is resolved here, at call time, from the engine's own live `_upcomingQueue`,
 * then translated to a real timeline index via `upcomingTimelineIndices` as usual. A position
 * captured further up (in the UI, at gesture time) can be invalidated by any queue mutation
 * that lands first — every row after the mutation shifts — and would then name whichever song
 * slid into that slot, removing it permanently. Resolving against live state closes that gap.
 *
 * Always permanent, regardless of shuffle state — see [QueueBaselineManager.remove].
 */
internal fun ExoPlayerEngine.removeQueueItemInternal(songId: String) {
    val ctrl = controller ?: return
    val displayIndex = _upcomingQueue.value.indexOfFirst { it.id == songId }
    if (displayIndex < 0) return
    val realIndex = upcomingTimelineIndices.getOrNull(displayIndex) ?: return
    // Last identity check before the destructive call: if engine state has drifted from the
    // timeline, drop the removal rather than delete whatever else now sits at realIndex.
    if (realIndex >= ctrl.mediaItemCount || ctrl.getMediaItemAt(realIndex).mediaId != songId) return
    ctrl.removeMediaItem(realIndex)

    baselineManager.remove(songId)
    songsById.remove(songId)

    onQueueStructureChanged(fresh = false)
}

/** [index] is a position in `upcomingQueue`, translated to the real timeline index. */
internal fun ExoPlayerEngine.seekToQueueItemInternal(index: Int) {
    val ctrl = controller ?: return
    val realIndex = upcomingTimelineIndices.getOrNull(index) ?: return
    ctrl.seekTo(realIndex, C.TIME_UNSET)
    ctrl.play()
}
