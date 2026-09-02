package com.v1per.synthx.playback

import androidx.media3.session.MediaController
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song

/**
 * Owns the "baseline" order — the app-managed source-of-truth queue sequence. Starts as
 * whatever [ExoPlayerEngine.playQueue] was called with, but is mutable going forward (see
 * each method below for exactly when and how). Distinct from the engine's live `_queue`,
 * which is whatever order is actually playing right now (shuffled or not).
 *
 * Pure list/persistence bookkeeping — unlike the rest of the engine, nothing here touches
 * [MediaController] or the timeline directly; [ExoPlayerEngine] is responsible for applying
 * whatever list this produces to the live timeline (see [applyReorderViaMoves]). Persisted
 * separately (`queue_baseline_items`, via [QueuePersistence.saveBaselineQueue]) so it
 * survives a restart independently of the active queue.
 */
class QueueBaselineManager(private val persistence: QueuePersistence) {

    var current: List<QueueSlot> = emptyList()
        private set

    /** A fresh [ExoPlayerEngine.playQueue] call always fully replaces baseline — never merges with whatever existed before. */
    fun captureFresh(slots: List<QueueSlot>) {
        current = slots
        persistence.saveBaselineQueue(slots)
    }

    /** Restore path — baseline was already loaded from storage by the caller, so this just adopts it; nothing to re-persist. */
    fun restore(slots: List<QueueSlot>) {
        current = slots
    }

    /**
     * [ExoPlayerEngine.playNext] while shuffle is off — [slot] is woven into baseline right
     * after the current song, permanently. While shuffle is on, callers simply don't call
     * this; the song is folded into baseline later, by [computeUnshuffleTarget].
     */
    fun weaveNext(slot: QueueSlot, currentSlotId: String?) {
        val insertAt = if (currentSlotId != null) {
            current.indexOfFirst { it.slotId == currentSlotId } + 1
        } else {
            current.size
        }
        current = current.toMutableList().apply { add(insertAt.coerceIn(0, size), slot) }
        persistence.saveBaselineQueue(current)
    }

    /** [ExoPlayerEngine.addToQueue] while shuffle is off — same permanence rule as [weaveNext], appended at the end instead. */
    fun weaveEnd(slot: QueueSlot) {
        current = current + slot
        persistence.saveBaselineQueue(current)
    }

    /**
     * [ExoPlayerEngine.moveQueueItem] while shuffle is off — the active queue's upcoming
     * order *is* baseline's tail while unshuffled, so the display indices line up via
     * `baseline.indexOf(current) + 1 + displayIndex`. A no-op if [currentSlotId] can't be
     * found in baseline, or either translated index falls outside it.
     */
    fun mirrorMove(fromDisplayIndex: Int, toDisplayIndex: Int, currentSlotId: String?) {
        val baseIndex = currentSlotId?.let { id -> current.indexOfFirst { it.slotId == id } } ?: -1
        if (baseIndex < 0) return
        val baseFrom = baseIndex + 1 + fromDisplayIndex
        val baseTo = baseIndex + 1 + toDisplayIndex
        if (baseFrom !in current.indices || baseTo !in current.indices) return
        current = current.toMutableList().apply { add(baseTo, removeAt(baseFrom)) }
        persistence.saveBaselineQueue(current)
    }

    /**
     * [ExoPlayerEngine.removeQueueItem] — always permanent, regardless of shuffle state:
     * removing a slot means "don't play this occurrence again," not "hide it while shuffled,"
     * so that exact slot is unconditionally stripped from baseline too.
     */
    fun remove(slotId: String) {
        current = current.filterNot { it.slotId == slotId }
        persistence.saveBaselineQueue(current)
    }

    /** Replaces content data under every matching slot while preserving each occurrence id. */
    fun remapSong(oldSongId: String, newSong: Song) {
        val remapped = current.map { slot ->
            if (slot.song.id == oldSongId) slot.copy(song = newSong) else slot
        }
        if (remapped != current) {
            current = remapped
            persistence.saveBaselineQueue(current)
        }
    }

    /** Shuffle ON: [current] stays first, everything else in baseline (played or not) is reshuffled after it. Does not mutate baseline. */
    fun computeShuffleTarget(current: QueueSlot): List<QueueSlot> {
        return listOf(current) + this.current.filterNot { it.slotId == current.slotId }.shuffled()
    }

    /**
     * Shuffle OFF: [current] stays first, followed by whatever comes after it in baseline —
     * anything *before* it is dropped, permanently, from both the returned order and
     * baseline itself (accepted, intentional behaviour, not a bug) — plus any songs added
     * via playNext/addToQueue during the shuffle-ON session just ending, appended at the end
     * in their current live order ([liveQueue], derived by set difference against baseline,
     * not tracked separately). The result becomes the new baseline, persisted as such.
     */
    fun computeUnshuffleTarget(current: QueueSlot, liveQueue: List<QueueSlot>): List<QueueSlot> {
        val currentBaselineIndex = this.current.indexOfFirst { it.slotId == current.slotId }
        val afterCurrentInBaseline = if (currentBaselineIndex >= 0) {
            this.current.subList(currentBaselineIndex + 1, this.current.size)
        } else {
            emptyList()
        }
        val baselineIds = this.current.mapTo(HashSet()) { it.slotId }
        val sessionExtras = liveQueue.filterNot {
            it.slotId == current.slotId || it.slotId in baselineIds
        }
        val target = listOf(current) + afterCurrentInBaseline + sessionExtras
        this.current = target
        persistence.saveBaselineQueue(target)
        return target
    }
}

/**
 * Transforms the live timeline into [target] using only `removeMediaItem`/`moveMediaItem`
 * — selection sort by position for the reorder, with a first pass dropping anything the
 * live timeline holds that [target] does not (only relevant going shuffle-ON to OFF, where
 * baseline history before the current song is intentionally dropped — see
 * [QueueBaselineManager.computeUnshuffleTarget]). Removing items strictly before the
 * currently-playing one does not change its period identity, so this does not interrupt
 * playback either.
 *
 * Never `setMediaItems` — confirmed via the media3 1.4.0 `ExoPlayerImpl` source to
 * unconditionally tear down and recreate every `MediaSourceHolder`, including the
 * currently-playing one, which rebuffers/interrupts it. `moveMediaItem` reorders the same
 * holders in place instead, so the playing item is never torn down.
 */
internal fun applyReorderViaMoves(ctrl: MediaController, target: List<QueueSlot>) {
    val targetIds = target.mapTo(HashSet()) { it.slotId }
    for (i in ctrl.mediaItemCount - 1 downTo 0) {
        if (ctrl.getMediaItemAt(i).mediaId !in targetIds) {
            ctrl.removeMediaItem(i)
        }
    }
    for (targetIndex in target.indices) {
        val currentPos = indexOfMediaId(ctrl, target[targetIndex].slotId) ?: continue
        if (currentPos != targetIndex) {
            ctrl.moveMediaItem(currentPos, targetIndex)
        }
    }
}
