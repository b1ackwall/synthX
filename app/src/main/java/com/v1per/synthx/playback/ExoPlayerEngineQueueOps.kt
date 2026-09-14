package com.v1per.synthx.playback

import android.util.Log
import androidx.media3.common.C
import com.v1per.synthx.data.PlaybackCacheRevision
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val TAG = "SynthXQueue"

/**
 * Real bodies for [ExoPlayerEngine]'s [PlaybackEngine] queue-mutation overrides — the class
 * itself keeps only one-line delegators (`override fun playQueue(...) = playQueueInternal(...)`)
 * because Kotlin requires `override` members to be declared inside the class body; these
 * `xxxInternal` extension functions are where the actual logic lives, relocated here purely to
 * keep `ExoPlayerEngine.kt` shorter.
 *
 * Every one of them now follows the same two-step shape: **mutate the order store, then
 * reconcile the window.** The store edit is a cheap pure list operation that always succeeds
 * regardless of queue size or where in the queue it lands; [maintainWindow] afterwards is a
 * no-op when the edit fell outside the loaded window, and a handful of bounded controller calls
 * when it did not. That replaces the old shape, where every mutation had to translate a display
 * position into a real timeline index and could silently no-op if the target was not loaded.
 *
 * Building `MediaItem`s no longer needs its own dispatcher hop either: the most that is ever
 * built at once is [WINDOW_SIZE] of them, where it used to be one per song in the queue.
 */

internal suspend fun ExoPlayerEngine.playQueueInternal(songs: List<Song>, startIndex: Int) {
    if (songs.isEmpty()) return
    // Captured BEFORE the snapshot below and the artwork suspension that follows — see
    // reconcileIfRevisionChanged's kdoc (P4-T2). A remap/deletion landing in that window is
    // checked against a cache this call hasn't installed yet, correctly (but now stalely)
    // finds nothing to do, and is discarded; the revision bump alone is what lets this call
    // notice, after installing, that its own snapshot may already be wrong.
    val revisionBeforeSnapshot = PlaybackCacheRevision.current()
    val start = startIndex.coerceIn(0, songs.lastIndex)
    val slots = songs.map { song -> QueueSlot(UUID.randomUUID().toString(), song) }
    val startSlot = slots[start]

    // Only the starting item gets its artwork up front — building bytes for every song in the
    // queue would mean decoding/generating hundreds of bitmaps before a single note plays. The
    // rest are filled in by prefetchArtwork() as they approach.
    val startArtwork = buildArtworkData(startSlot.song)

    // MediaController is main-thread-only and buildArtworkData above may have moved us off it.
    withContext(Dispatchers.Main.immediate) {
        val ctrl = awaitController() ?: return@withContext
        songsBySlotId.clear()
        slots.forEach { songsBySlotId[it.slotId] = it.song }
        artworkLoadedSlotIds.clear()
        artworkLoadedSlotIds.add(startSlot.slotId)

        // Store before timeline: every queue flow is derived from the store now, so it has to
        // be correct before the transition callback for the starting item can land. A fresh
        // queue always fully replaces both orders — never merges with whatever existed before.
        val baselineRecords = slots.map { QueueSlotRecord(it.slotId, it.song.id) }
        if (_shuffleEnabled.value) {
            // F12: a fresh play issued while shuffle is already on must actually shuffle the
            // incoming queue rather than leaving the flag true against an unshuffled order —
            // the previous behaviour silently left flag and order disagreeing, which is also
            // what made re-enabling shuffle later read as a rejected no-op.
            val startRecord = baselineRecords[start]
            val liveRecords = listOf(startRecord) + (baselineRecords - startRecord).shuffled()
            orderStore.captureFreshShuffled(liveRecords, baselineRecords, writeBaseline = false)
        } else {
            orderStore.captureFresh(baselineRecords, writeBaseline = false)
        }
        refreshQueue()
        publishCurrentSlot(startSlot.slotId)
        refreshCurrentIndex()

        val playIndex = orderStore.indexOfSlot(startSlot.slotId).coerceAtLeast(0)
        seedWindow(playIndex, C.TIME_UNSET, startArtwork)
        ctrl.play()

        refreshUpcomingQueue()
        refreshNavigationState()
        _isQueueCustomized.value = false
        // Baseline + active order + shuffle + repeat + cursor, all in one atomic write
        // (P4-T6 / F11) — replaces the old shape (captureFresh's own separate baseline write,
        // then onQueueStructureChanged's separate persistQueue), which could previously land as
        // two independent commits.
        persistUnifiedQueueState()
        prefetchArtwork()
    }
    // After install, not inside the Main.immediate block above: reconciliation does its own
    // suspending DB read and re-enters Main.immediate itself.
    reconcileIfRevisionChanged(revisionBeforeSnapshot)
}

/**
 * Restores without playing: `MediaController.prepare` buffers to STATE_READY but playWhenReady
 * stays false, so the mini player comes up loaded and paused (Apple Music's behaviour) and the
 * user starts it.
 */
internal suspend fun ExoPlayerEngine.restoreSavedQueueInternal() {
    Log.d(TAG, "Queue restore begin")
    // See playQueueInternal's identical capture — this path has THREE suspension points before
    // install (restored-songs read, artwork, baseline read), each an equally valid window for a
    // revision bump to land in.
    val revisionBeforeSnapshot = PlaybackCacheRevision.current()
    val restored = persistence.loadQueue()
    if (restored == null) {
        Log.d(TAG, "Queue restore: no persisted queue; starting fresh")
        return
    }
    val startSlot = restored.slots[restored.startIndex]
    Log.d(
        TAG,
        "Queue restore loaded: songs=${restored.slots.size}, startIndex=${restored.startIndex}, " +
            "slot=${startSlot.slotId}",
    )
    val startArtwork = buildArtworkData(startSlot.song)
    // Loaded alongside the active queue rather than derived from it — baseline can legitimately
    // differ from the active list (e.g. history dropped by an ON->OFF toggle last session), so
    // it needs its own read, not a recomputation.
    val restoredBaseline = persistence.loadBaselineQueue()
    var installed = false

    withContext(Dispatchers.Main.immediate) {
        val ctrl = awaitController() ?: return@withContext
        // Not restored over an in-progress session: the user has already started something, and
        // replacing it with last session's queue would be a surprise.
        if (ctrl.mediaItemCount > 0) {
            Log.d(
                TAG,
                "Queue restore skipped: adopting existing running timeline; " +
                    "slot=${_currentSlotId.value}, queue=${orderStore.size}",
            )
            return@withContext
        }

        songsBySlotId.clear()
        restored.slots.forEach { songsBySlotId[it.slotId] = it.song }
        restoredBaseline.forEach { songsBySlotId.putIfAbsent(it.slotId, it.song) }
        artworkLoadedSlotIds.clear()
        artworkLoadedSlotIds.add(startSlot.slotId)

        orderStore.restore(
            live = restored.slots.map { QueueSlotRecord(it.slotId, it.song.id) },
            baseline = restoredBaseline.map { QueueSlotRecord(it.slotId, it.song.id) },
        )
        // Plain field sets — no media3 involvement, no rebuild. The active order just loaded
        // already reflects whatever order (shuffled or not) was live when the app last closed.
        // Repeat has to be adopted here rather than left to the callback `ctrl.repeatMode =`
        // below triggers, because the window built a few lines down reads it for its
        // repeat-all wraparound rule.
        _shuffleEnabled.value = restored.shuffleEnabled
        _repeatMode.value = restored.repeatMode.toRepeatMode()
        // Populate queue state before publishing cursor identity, preserving the original
        // pre-windowing ordering guarantee as defense in depth for any legacy consumers.
        refreshQueue()
        publishCurrentSlot(startSlot.slotId)
        refreshCurrentIndex()

        seedWindow(restored.startIndex, restored.positionMs, startArtwork)
        ctrl.repeatMode = restored.repeatMode

        // Not routed through onQueueStructureChanged: the persist below is conditional, and a
        // restored queue counts as pristine — the customisation flag is session state that
        // deliberately does not survive the restart.
        refreshUpcomingQueue()
        refreshNavigationState()
        _isQueueCustomized.value = false
        // The stored rows disagree with what actually loaded — rewrite them so the saved cursor
        // and the queue cannot drift further apart on the next restore. Written from the order
        // store, which is the full restored order, not from the window just seeded.
        if (restored.droppedCount > 0) {
            Log.i(
                ExoPlayerEngine.TAG,
                "Restored ${restored.slots.size} queue items, " +
                    "${restored.droppedCount} no longer available",
            )
            persistence.saveQueueRecords(orderStore.live, startSlot.slotId, restored.positionMs)
        }
        prefetchArtwork()
        Log.d(
            TAG,
            "Queue restore complete: song=${_currentSong.value?.id}, " +
                "slot=${_currentSlotId.value}, queue=${orderStore.size}",
        )
        installed = true
    }
    // Only when this call actually installed a snapshot — the "adopting existing running
    // timeline" bail-out above did not, and has nothing here for a revision bump to invalidate.
    if (installed) reconcileIfRevisionChanged(revisionBeforeSnapshot)
}

/**
 * Builds the first window of a session and hands it to the player in one `setMediaItems`.
 * Distinct from [rebuildWindowAround] only in that it carries pre-fetched artwork for the
 * starting item and leaves `play()`/`prepare()` sequencing to the caller, so the restore path
 * can come up paused.
 */
private fun ExoPlayerEngine.seedWindow(
    startIndex: Int,
    positionMs: Long,
    startArtwork: ByteArray,
) {
    val ctrl = controller ?: return
    val startSlotId = orderStore.slotAt(startIndex)?.slotId ?: return
    val window = computeWindow(
        live = orderStore.live,
        currentIndex = startIndex,
        wrapAround = _repeatMode.value == RepeatMode.All,
    )
    val windowStart = window.indexOfFirst { it.slotId == startSlotId }.coerceAtLeast(0)
    ctrl.setMediaItems(
        window.mapNotNull { record ->
            songsBySlotId[record.slotId]?.let { song ->
                buildMediaItem(
                    record.slotId,
                    song,
                    if (record.slotId == startSlotId) startArtwork else null,
                )
            }
        },
        windowStart,
        positionMs,
    )
    ctrl.prepare()
}

/**
 * Shuffle ON: the current slot stays first, everything else in baseline (played or not) is
 * reshuffled after it. Shuffle OFF: the current slot stays first, followed by whatever comes
 * after it in baseline, plus anything added via Play Next / Add to Queue during the shuffle-ON
 * session just ending; that result also becomes the new baseline.
 *
 * The reorder itself is now a pure permutation of the order store's records — no `MediaItem`s,
 * no timeline rebuild — followed by [maintainWindow] reconciling the ~[WINDOW_SIZE] items that
 * are actually loaded. The currently-playing item is never removed and re-added by that
 * reconciliation, so unlike the old large-queue `setMediaItems` path this does not tear down
 * and rebuffer the playing track, and unlike the old move-based path it does not scale with
 * queue size. That is what let the size threshold between those two paths be deleted outright.
 */
internal suspend fun ExoPlayerEngine.setShuffleEnabledInternal(enabled: Boolean) {
    withContext(Dispatchers.Main.immediate) {
        if (enabled == _shuffleEnabled.value) return@withContext
        controller ?: return@withContext
        val currentSlotId = _currentSlotId.value ?: return@withContext

        val reordered = if (enabled) {
            orderStore.shuffle(currentSlotId)
        } else {
            // writeBaseline=false: baseline is rewritten again as part of the SAME atomic write
            // below (P4-T6 / F11) — letting this land as its own independent write would reopen
            // exactly the torn-write window unification exists to close.
            orderStore.unshuffle(currentSlotId, writeBaseline = false)
        }
        if (!reordered) return@withContext

        // Shuffle OFF only: unshuffling permanently drops baseline history before the current
        // song, so anything else still cached is now stale. Shuffle ON's result is a subset of
        // what is already tracked, so nothing needs pruning there.
        if (!enabled) {
            songsBySlotId.keys.retainAll(orderStore.live.mapTo(HashSet()) { it.slotId })
        }

        _shuffleEnabled.value = enabled
        maintainWindow(force = true)
        refreshQueue()
        refreshUpcomingQueue()
        refreshNavigationState()
        // Baseline + active order + shuffle + repeat + cursor, all in one atomic write
        // (P4-T6 / F11) — replaces the old shape (a separate updateShuffleEnabled write, plus
        // onQueueReordered's separate persistQueue, plus — while turning shuffle off —
        // unshuffle's own separate baseline write): three independent commits for one logical
        // change, any pair of which a process death between them could leave mismatched.
        persistUnifiedQueueState()
        prefetchArtwork()
    }
}

/**
 * While shuffle is off, [song] is also woven into baseline at the matching relative slot —
 * permanently, per the locked spec (see [QueueOrderStore.insertAfterCurrent]). While shuffle is
 * on, baseline is left untouched; if shuffle later turns off, [song] is folded into the new
 * baseline then (see [setShuffleEnabledInternal]), not here.
 */
internal fun ExoPlayerEngine.playNextInternal(song: Song) {
    controller ?: return
    val slot = QueueSlot(UUID.randomUUID().toString(), song)
    songsBySlotId[slot.slotId] = song
    orderStore.insertAfterCurrent(
        record = QueueSlotRecord(slot.slotId, song.id),
        currentSlotId = _currentSlotId.value,
        mirrorToBaseline = !_shuffleEnabled.value,
    )
    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * Same baseline-weaving rule as [playNextInternal], appended at the end instead of next-slot.
 * On a large queue the new slot lands far outside the loaded window, so [maintainWindow] has
 * nothing to do and this costs no controller work at all.
 */
internal fun ExoPlayerEngine.addToQueueInternal(song: Song) {
    controller ?: return
    val slot = QueueSlot(UUID.randomUUID().toString(), song)
    songsBySlotId[slot.slotId] = song
    orderStore.append(
        record = QueueSlotRecord(slot.slotId, song.id),
        mirrorToBaseline = !_shuffleEnabled.value,
    )
    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * [fromIndex]/[toIndex] are positions in `upcomingQueue`, applied directly to the order store —
 * no translation into timeline indices any more, which is what makes dragging a row from deep
 * outside the loaded window work rather than silently no-op.
 *
 * While shuffle is off the same move is mirrored into baseline. While shuffle is on this only
 * touches the live order; the reorder evaporates the moment shuffle turns off, per spec.
 */
internal fun ExoPlayerEngine.moveQueueItemInternal(fromIndex: Int, toIndex: Int) {
    controller ?: return
    val moved = orderStore.moveUpcoming(
        fromDisplayIndex = fromIndex,
        toDisplayIndex = toIndex,
        currentSlotId = _currentSlotId.value,
        mirrorToBaseline = !_shuffleEnabled.value,
    )
    if (!moved) return
    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
}

/**
 * Removes the item identified by [slotId] — no position is accepted from the caller. A position
 * captured further up (in the UI, at gesture time) can be invalidated by any queue mutation
 * that lands first — every row after the mutation shifts — and would then name whichever song
 * slid into that slot, removing it permanently. Resolving by stable slot id against the order
 * store closes that gap, and does so whether or not the slot is currently loaded.
 *
 * The currently-playing slot is explicitly not removable, matching the old behaviour (it is
 * never one of the Up Next rows the gesture can reach) and keeping the window's anchor alive.
 *
 * Always permanent, regardless of shuffle state — see [QueueOrderStore.remove].
 */
internal fun ExoPlayerEngine.removeQueueItemInternal(slotId: String) {
    controller ?: return
    if (slotId == _currentSlotId.value) return
    if (!orderStore.remove(slotId)) return

    songsBySlotId.remove(slotId)
    artworkLoadedSlotIds.remove(slotId)

    maintainWindow(force = true)
    onQueueStructureChanged(fresh = false)
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

/**
 * [index] is a position in `upcomingQueue`, resolved against the order store. A target already
 * inside the loaded window is a plain seek; anything further out rebuilds the window around it
 * first — an interruption, but a deliberate jump the user asked for, and bounded to
 * [WINDOW_SIZE] items rather than the whole queue.
 */
internal fun ExoPlayerEngine.seekToQueueItemInternal(index: Int) {
    controller ?: return
    val currentIndex = orderStore.indexOfSlot(_currentSlotId.value)
    if (currentIndex < 0) return
    val target = currentIndex + 1 + index
    if (target !in 0 until orderStore.size) return
    seekToLiveIndex(target, play = true)
}
