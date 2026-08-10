package com.v1per.synthx.playback

import androidx.media3.session.MediaController
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

    var current: List<Song> = emptyList()
        private set

    /** A fresh [ExoPlayerEngine.playQueue] call always fully replaces baseline — never merges with whatever existed before. */
    fun captureFresh(songs: List<Song>) {
        current = songs
        persistence.saveBaselineQueue(songs)
    }

    /** Restore path — baseline was already loaded from storage by the caller, so this just adopts it; nothing to re-persist. */
    fun restore(songs: List<Song>) {
        current = songs
    }

    /**
     * [ExoPlayerEngine.playNext] while shuffle is off — [song] is woven into baseline right
     * after the current song, permanently. While shuffle is on, callers simply don't call
     * this; the song is folded into baseline later, by [computeUnshuffleTarget].
     */
    fun weaveNext(song: Song, currentSongId: String?) {
        val insertAt = if (currentSongId != null) {
            current.indexOfFirst { it.id == currentSongId } + 1
        } else {
            current.size
        }
        current = current.toMutableList().apply { add(insertAt.coerceIn(0, size), song) }
        persistence.saveBaselineQueue(current)
    }

    /** [ExoPlayerEngine.addToQueue] while shuffle is off — same permanence rule as [weaveNext], appended at the end instead. */
    fun weaveEnd(song: Song) {
        current = current + song
        persistence.saveBaselineQueue(current)
    }

    /**
     * [ExoPlayerEngine.moveQueueItem] while shuffle is off — the active queue's upcoming
     * order *is* baseline's tail while unshuffled, so the display indices line up via
     * `baseline.indexOf(current) + 1 + displayIndex`. A no-op if [currentSongId] can't be
     * found in baseline, or either translated index falls outside it.
     */
    fun mirrorMove(fromDisplayIndex: Int, toDisplayIndex: Int, currentSongId: String?) {
        val baseIndex = currentSongId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
        if (baseIndex < 0) return
        val baseFrom = baseIndex + 1 + fromDisplayIndex
        val baseTo = baseIndex + 1 + toDisplayIndex
        if (baseFrom !in current.indices || baseTo !in current.indices) return
        current = current.toMutableList().apply { add(baseTo, removeAt(baseFrom)) }
        persistence.saveBaselineQueue(current)
    }

    /**
     * [ExoPlayerEngine.removeQueueItem] — always permanent, regardless of shuffle state:
     * removing a song means "don't play this again," not "hide it while shuffled," so
     * baseline is unconditionally stripped of it too, by song id.
     */
    fun remove(songId: String) {
        current = current.filterNot { it.id == songId }
        persistence.saveBaselineQueue(current)
    }

    /** Shuffle ON: [current] stays first, everything else in baseline (played or not) is reshuffled after it. Does not mutate baseline. */
    fun computeShuffleTarget(current: Song): List<Song> {
        return listOf(current) + this.current.filterNot { it.id == current.id }.shuffled()
    }

    /**
     * Shuffle OFF: [current] stays first, followed by whatever comes after it in baseline —
     * anything *before* it is dropped, permanently, from both the returned order and
     * baseline itself (accepted, intentional behaviour, not a bug) — plus any songs added
     * via playNext/addToQueue during the shuffle-ON session just ending, appended at the end
     * in their current live order ([liveQueue], derived by set difference against baseline,
     * not tracked separately). The result becomes the new baseline, persisted as such.
     */
    fun computeUnshuffleTarget(current: Song, liveQueue: List<Song>): List<Song> {
        val currentBaselineIndex = this.current.indexOfFirst { it.id == current.id }
        val afterCurrentInBaseline = if (currentBaselineIndex >= 0) {
            this.current.subList(currentBaselineIndex + 1, this.current.size)
        } else {
            emptyList()
        }
        val baselineIds = this.current.mapTo(HashSet()) { it.id }
        val sessionExtras = liveQueue.filterNot { it.id == current.id || it.id in baselineIds }
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
internal fun applyReorderViaMoves(ctrl: MediaController, target: List<Song>) {
    val targetIds = target.mapTo(HashSet()) { it.id }
    for (i in ctrl.mediaItemCount - 1 downTo 0) {
        if (ctrl.getMediaItemAt(i).mediaId !in targetIds) {
            ctrl.removeMediaItem(i)
        }
    }
    for (targetIndex in target.indices) {
        val currentPos = indexOfMediaId(ctrl, target[targetIndex].id) ?: continue
        if (currentPos != targetIndex) {
            ctrl.moveMediaItem(currentPos, targetIndex)
        }
    }
}
