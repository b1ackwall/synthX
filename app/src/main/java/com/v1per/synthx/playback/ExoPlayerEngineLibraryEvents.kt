package com.v1per.synthx.playback

import com.v1per.synthx.data.SongRemap

// ExoPlayerEngine's reactions to the library changing under a live queue.

/** The remap-collector body, split out so it can be wrapped in a try/catch above. */
internal fun ExoPlayerEngine.handleRemap(remap: SongRemap) {
    val affectedSlotIds = songsBySlotId
        .filterValues { it.id == remap.oldId }
        .keys
        .toList()
    if (affectedSlotIds.isEmpty()) return
    affectedSlotIds.forEach { slotId ->
        songsBySlotId[slotId] = remap.newSong
        artworkLoadedSlotIds.remove(slotId)
    }
    orderStore.remapSong(remap.oldId, remap.newSong.id)
    val ctrl = controller
    affectedSlotIds.forEach { slotId ->
        // A null index simply means that slot is not in the loaded window —
        // an ordinary answer now, not a drift signal. Its refreshed Song is
        // already in songsBySlotId, so it comes back correct whenever the
        // window next reaches it.
        val index = ctrl?.let { indexOfMediaId(it, slotId) } ?: return@forEach
        ctrl.replaceMediaItem(
            index,
            buildMediaItem(slotId, remap.newSong, artworkData = null),
        )
    }
    refreshQueue()
    refreshUpcomingQueue()
    val currentSlotId = _currentSlotId.value
    if (currentSlotId != null && currentSlotId in affectedSlotIds) {
        publishCurrentSlot(currentSlotId)
    }
    persistQueue()
    prefetchArtwork()
}

/**
 * Strips every queue occurrence of a song whose row has just been deleted from the library — the
 * live-state half of complete delete, driven by
 * [com.v1per.synthx.data.SongDeletionEvents]. The song's `queue_items`/`queue_baseline_items`
 * rows are already gone via FK CASCADE by the time this runs; this is what stops the in-memory
 * order store and the loaded timeline from continuing to reference a song that no longer exists
 * and whose audio file has been (or is about to be) removed from disk.
 *
 * Deliberately not built on [removeQueueItemInternal], for two reasons. That function refuses to
 * touch the currently-playing slot — correct for the Up Next swipe gesture it exists for, but the
 * one case that matters most here — and it fires a full [onQueueStructureChanged] per slot, which
 * would both persist N times for one deletion and wrongly flag the queue as user-customised.
 *
 * The playing slot is handled first, before anything is removed, so playback transitions off the
 * deleted song cleanly rather than running into a file that is no longer there: skip to the next
 * queued item that is not also this song, or stop outright when nothing follows.
 */
internal fun ExoPlayerEngine.removeDeletedSongInternal(songId: String) {
    val ctrl = controller ?: return
    val affectedSlotIds = songsBySlotId.filterValues { it.id == songId }.keys.toList()
    if (affectedSlotIds.isEmpty()) return

    if (_currentSlotId.value in affectedSlotIds) {
        val target = nextLiveIndexExcludingSong(songId)
        if (target == null) {
            // Nothing left to play. The order store keeps whatever else is queued (there may be
            // none), but the cursor has to leave the deleted slot — stop() is the engine's own
            // "playback left the queue" path and publishes a null current slot for us.
            stop()
            // Explicitly wipe the controller's own timeline here too (P4-T4 / R3-F14): stop()
            // alone clears the engine's cursor but leaves whatever history Media3 still has
            // loaded (e.g. queue [A, B, B], playing the last B — A and the first B are still
            // loaded behind it). Media3 must then resolve SOME new "current item" from that
            // remaining timeline as the removals below land, and onMediaItemTransition would
            // otherwise republish that leftover history slot right back into the engine,
            // silently undoing the null cursor stop() just published. Clearing here removes the
            // only thing Media3 could have resolved a new current item from, so a transition
            // callback that fires after this carries a null MediaItem — which is exactly what
            // publishCurrentSlot already treats as "nothing playing".
            ctrl.clearMediaItems()
        } else {
            val targetSlotId = orderStore.slotAt(target)?.slotId
            // play only if it was already playing: a deletion is not a reason to start audio,
            // and seekToLiveIndex leaves playWhenReady alone otherwise.
            seekToLiveIndex(target, play = _isPlaying.value)
            // Published directly rather than waiting for onMediaItemTransition to land: the
            // removal below must not run while the cursor still names a slot it is about to
            // delete, and the callback would only republish this same value a frame later.
            // Same manual-publish shape playQueueInternal already uses when it seeds a queue.
            if (targetSlotId != null) publishCurrentSlot(targetSlotId)
        }
    }

    // One batch removal rather than one orderStore.remove() call per occurrence (P4-T4 /
    // R3-F15): per-occurrence calls each copy/filter the full live+baseline lists and, if
    // baseline changed, trigger their own whole-baseline persistence write — quadratic work
    // and N writes (through an unbounded channel) for N duplicate occurrences of one song.
    val removedAny = orderStore.removeAll(affectedSlotIds)
    affectedSlotIds.forEach { slotId ->
        songsBySlotId.remove(slotId)
        artworkLoadedSlotIds.remove(slotId)
    }
    if (!removedAny) return

    // Dropped from the loaded timeline explicitly rather than left to maintainWindow: on the
    // stop() path above the cursor is null, so maintainWindow bails without reconciling anything
    // and a MediaItem for a now-deleted file would stay loaded in the session. Indices are
    // resolved fresh per slot because each removal shifts the ones after it. Idempotent with the
    // maintainWindow below, which then finds nothing left to remove.
    affectedSlotIds.forEach { slotId ->
        indexOfMediaId(ctrl, slotId)?.let { ctrl.removeMediaItem(it) }
    }

    maintainWindow(force = true)
    // onQueueReordered, not onQueueStructureChanged: this is a system-driven removal, not a hand
    // edit, so it must not flip _isQueueCustomized and trigger the Keep/Clear prompt for a user
    // who never reordered anything — exactly the distinction that function was split out for.
    onQueueReordered()
}

/**
 * The next position in the full order that is *not* another occurrence of [songId] — the deleted
 * song can legitimately sit in the queue several times over, including back to back, and skipping
 * onto one of its own siblings would just land on the same missing file.
 *
 * Walks with [nextIndexInOrder] so repeat-mode wraparound behaves exactly as ordinary skip-forward
 * does, bounded by the queue length so an all-one-song queue under Repeat All terminates.
 */
private fun ExoPlayerEngine.nextLiveIndexExcludingSong(songId: String): Int? {
    val count = orderStore.size
    var index = orderStore.indexOfSlot(_currentSlotId.value)
    repeat(count) {
        val candidate = nextIndexInOrder(count, index, _repeatMode.value) ?: return null
        if (orderStore.slotAt(candidate)?.songId != songId) return candidate
        index = candidate
    }
    return null
}
