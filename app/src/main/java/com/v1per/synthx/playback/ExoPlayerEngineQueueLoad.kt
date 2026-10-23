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

internal suspend fun ExoPlayerEngine.playQueueInternal(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
    if (songs.isEmpty()) return
    // Captured BEFORE the snapshot below and the artwork suspension that follows — see
    // reconcileIfRevisionChanged's kdoc. A remap/deletion landing in that window is
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
        // Waits out a connection still being made or retried rather than dropping
        // the tap; null only once the whole round of attempts has failed.
        val ctrl = awaitController() ?: run {
            reportUnavailable("playQueue")
            return@withContext
        }
        songsBySlotId.clear()
        slots.forEach { songsBySlotId[it.slotId] = it.song }
        artworkLoadedSlotIds.clear()
        artworkLoadedSlotIds.add(startSlot.slotId)

        // Store before timeline: every queue flow is derived from the store, so it has to
        // be correct before the transition callback for the starting item can land. A fresh
        // queue always fully replaces both orders — never merges with whatever existed before.
        val baselineRecords = slots.map { QueueSlotRecord(it.slotId, it.song.id) }
        // An explicit choice lands before the order is built, so flag, order and the one unified
        // write below are a single step — no current slot needed, unlike setShuffleEnabled.
        if (shuffle != null) _shuffleEnabled.value = shuffle
        if (_shuffleEnabled.value) {
            // A fresh play issued while shuffle is already on must actually shuffle the
            // incoming queue rather than leaving the flag true against an unshuffled order;
            // a flag and order that disagree also make re-enabling shuffle later read as a
            // rejected no-op.
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
        // Baseline + active order + shuffle + repeat + cursor, all in one atomic write.
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
        // Same wait as playQueueInternal, so a launch-time restore survives a failed first
        // connection attempt that a retry then recovers. Logged rather than surfaced on errors:
        // nobody tapped anything to restore.
        val ctrl = awaitController() ?: run {
            Log.w(TAG, "Queue restore skipped: could not connect to the playback session")
            return@withContext
        }
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
        // Populate queue state before publishing cursor identity, as defense in depth for any
        // consumer that depends on that ordering.
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
private fun ExoPlayerEngine.seedWindow(startIndex: Int, positionMs: Long, startArtwork: ByteArray) {
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
