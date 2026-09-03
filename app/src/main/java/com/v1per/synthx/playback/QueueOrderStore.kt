package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord

/**
 * The single side effect [QueueOrderStore] has: persisting the baseline order. Narrower than
 * the whole [QueuePersistence] surface on purpose — the store is otherwise pure list
 * bookkeeping, and depending on just this keeps it constructible (and unit-testable) without a
 * whole [com.v1per.synthx.data.repository.SongRepository] behind it. [QueuePersistence]
 * implements it directly, so production wiring is still a plain constructor argument.
 */
fun interface BaselineOrderWriter {
    fun writeBaselineOrder(records: List<QueueSlotRecord>)
}

/**
 * Owns the **full** intended play order — what "the queue" means everywhere in the app except
 * the live player itself.
 *
 * This is the inversion the windowed player is built on. Previously the ExoPlayer timeline was
 * the source of truth (`refreshQueue` read it back, and `queue_items` was a mirror written
 * *from* it), which forced every song in the queue to exist as a live `MediaItem`. Now this
 * store holds the order and the timeline holds a bounded window into it (see
 * `ExoPlayerEngineWindow.kt`); slot ids are what correlate the two, so an entry keeps its
 * identity whether or not it is currently loaded into the player.
 *
 * Two orders are held, exactly as before:
 *  - [live] — the order actually playing right now, shuffled or not.
 *  - [baseline] — the app-managed unshuffled sequence that survives shuffle toggles.
 *
 * Both are [QueueSlotRecord]s (slot id + song id), deliberately not `MediaItem`s and not full
 * `Song`s: 2000 records is ~150KB, which is why holding the whole order in memory was never the
 * problem — media3's per-item `Window`/`Period`/`MediaSourceHolder` objects were. Hydration to
 * real `Song`s for the UI happens in the engine, against its `songsBySlotId` cache.
 *
 * Absorbs the whole of the former `QueueBaselineManager` (every method below carrying a "was"
 * note maps 1:1 onto one of its methods) rather than sitting alongside it, so there is exactly
 * one source-of-truth concept and not two competing ones. Like that class, nothing here touches
 * a `MediaController` or the timeline — the engine applies whatever order this produces.
 *
 * Main-thread-confined by the same convention as the engine's `songsBySlotId`: every caller is
 * either a playback mutation or a `Player.Listener` callback, and both run there.
 */
class QueueOrderStore(private val baselineWriter: BaselineOrderWriter) {

    /** The full live/active order — shuffled order while shuffle is on. */
    var live: List<QueueSlotRecord> = emptyList()
        private set

    /** The full unshuffled baseline; see the class kdoc. */
    var baseline: List<QueueSlotRecord> = emptyList()
        private set

    val size: Int get() = live.size

    val isEmpty: Boolean get() = live.isEmpty()

    /** Position of [slotId] in [live], or -1. Slot id, never song id — duplicates are distinct. */
    fun indexOfSlot(slotId: String?): Int =
        if (slotId == null) -1 else live.indexOfFirst { it.slotId == slotId }

    fun slotAt(index: Int): QueueSlotRecord? = live.getOrNull(index)

    /**
     * Everything after [currentSlotId] in [live] — the Up Next list, in full. Empty when the
     * slot is unknown, which is the same "nothing to show" answer the old timeline walk gave.
     */
    fun upcomingFrom(currentSlotId: String?): List<QueueSlotRecord> {
        val index = indexOfSlot(currentSlotId)
        return if (index < 0) emptyList() else live.subList(index + 1, live.size)
    }

    /** Was `captureFresh`: a fresh `playQueue` fully replaces both orders, never merges. */
    fun captureFresh(records: List<QueueSlotRecord>) {
        live = records
        baseline = records
        persistBaseline()
    }

    /** Was `restore`: both orders were already read from storage, so this just adopts them. */
    fun restore(live: List<QueueSlotRecord>, baseline: List<QueueSlotRecord>) {
        this.live = live
        this.baseline = baseline
    }

    /**
     * Was `weaveNext`: Play Next. Always inserted into [live] right after the current slot.
     * [mirrorToBaseline] is "shuffle is off" — while shuffle is on baseline is left alone and
     * the slot is folded in later by [unshuffle], per the locked spec.
     */
    fun insertAfterCurrent(
        record: QueueSlotRecord,
        currentSlotId: String?,
        mirrorToBaseline: Boolean,
    ) {
        val liveIndex = indexOfSlot(currentSlotId)
        val insertAt = if (liveIndex >= 0) liveIndex + 1 else live.size
        live = live.toMutableList().apply { add(insertAt.coerceIn(0, size), record) }
        if (mirrorToBaseline) {
            val baseIndex = baseline.indexOfFirst { it.slotId == currentSlotId }
            val baseAt = if (baseIndex >= 0) baseIndex + 1 else baseline.size
            baseline = baseline.toMutableList().apply { add(baseAt.coerceIn(0, size), record) }
            persistBaseline()
        }
    }

    /** Was `weaveEnd`: Add to Queue — same permanence rule as [insertAfterCurrent], appended. */
    fun append(record: QueueSlotRecord, mirrorToBaseline: Boolean) {
        live = live + record
        if (mirrorToBaseline) {
            baseline = baseline + record
            persistBaseline()
        }
    }

    /**
     * Drag-reorder. [fromDisplayIndex]/[toDisplayIndex] are positions in the Up Next list, i.e.
     * offsets after the current slot, resolved here against [live] — which is why this works for
     * a row nowhere near the loaded window: the store holds the whole order, so there is no
     * "real timeline index" to translate into any more, and therefore no position outside the
     * window that can silently fail to resolve.
     *
     * Was `mirrorMove` for the baseline half: while shuffle is off the upcoming order is
     * baseline's tail, so the same display offset applies there too. While shuffle is on the
     * reorder is live-only and evaporates on the next unshuffle, per spec.
     */
    fun moveUpcoming(
        fromDisplayIndex: Int,
        toDisplayIndex: Int,
        currentSlotId: String?,
        mirrorToBaseline: Boolean,
    ): Boolean {
        val currentIndex = indexOfSlot(currentSlotId)
        if (currentIndex < 0) return false
        val from = currentIndex + 1 + fromDisplayIndex
        val to = currentIndex + 1 + toDisplayIndex
        if (from !in live.indices || to !in live.indices) return false
        live = live.toMutableList().apply { add(to, removeAt(from)) }
        if (mirrorToBaseline) mirrorMoveIntoBaseline(fromDisplayIndex, toDisplayIndex, currentSlotId)
        return true
    }

    private fun mirrorMoveIntoBaseline(
        fromDisplayIndex: Int,
        toDisplayIndex: Int,
        currentSlotId: String?,
    ) {
        val baseIndex = baseline.indexOfFirst { it.slotId == currentSlotId }
        if (baseIndex < 0) return
        val from = baseIndex + 1 + fromDisplayIndex
        val to = baseIndex + 1 + toDisplayIndex
        if (from !in baseline.indices || to !in baseline.indices) return
        baseline = baseline.toMutableList().apply { add(to, removeAt(from)) }
        persistBaseline()
    }

    /**
     * Was `remove`: always permanent, regardless of shuffle state — removing a slot means
     * "do not play this occurrence again", not "hide it while shuffled", so the exact slot is
     * stripped from both orders. Returns false when nothing matched.
     */
    fun remove(slotId: String): Boolean {
        val newLive = live.filterNot { it.slotId == slotId }
        val newBaseline = baseline.filterNot { it.slotId == slotId }
        if (newLive.size == live.size && newBaseline.size == baseline.size) return false
        live = newLive
        if (newBaseline.size != baseline.size) {
            baseline = newBaseline
            persistBaseline()
        }
        return true
    }

    /**
     * Was `remapSong`: a rescan can replace one content id while several independently keyed
     * slots reference it. Every matching value is refreshed; no stable slot key changes.
     */
    fun remapSong(oldSongId: String, newSongId: String): Boolean {
        val newLive = live.map { if (it.songId == oldSongId) it.copy(songId = newSongId) else it }
        val newBaseline =
            baseline.map { if (it.songId == oldSongId) it.copy(songId = newSongId) else it }
        if (newLive == live && newBaseline == baseline) return false
        val baselineChanged = newBaseline != baseline
        live = newLive
        if (baselineChanged) {
            baseline = newBaseline
            persistBaseline()
        }
        return true
    }

    /**
     * Was `computeShuffleTarget`, except it now applies the result instead of handing a list
     * back for the caller to push into the timeline: the current slot stays first, everything
     * else in baseline (played or not) is reshuffled after it. Baseline itself is untouched.
     *
     * [shuffler] is injectable purely so tests can pin the permutation; production always uses
     * `shuffled()`. Returns false when the current slot is unknown.
     */
    fun shuffle(
        currentSlotId: String?,
        shuffler: (List<QueueSlotRecord>) -> List<QueueSlotRecord> = { it.shuffled() },
    ): Boolean {
        val current = live.firstOrNull { it.slotId == currentSlotId } ?: return false
        live = listOf(current) + shuffler(baseline.filterNot { it.slotId == current.slotId })
        return true
    }

    /**
     * Was `computeUnshuffleTarget`: the current slot stays first, followed by whatever comes
     * after it in baseline — anything before it is dropped, permanently, from both the new order
     * and baseline itself (accepted, intentional behaviour, not a bug) — plus any slots added
     * via Play Next / Add to Queue during the shuffle-ON session just ending, appended in their
     * current live order (derived by set difference against baseline, not tracked separately).
     * The result becomes the new baseline as well as the new live order.
     */
    fun unshuffle(currentSlotId: String?): Boolean {
        val current = live.firstOrNull { it.slotId == currentSlotId } ?: return false
        val baselineIndex = baseline.indexOfFirst { it.slotId == current.slotId }
        val afterCurrentInBaseline = if (baselineIndex >= 0) {
            baseline.subList(baselineIndex + 1, baseline.size)
        } else {
            emptyList()
        }
        val baselineIds = baseline.mapTo(HashSet()) { it.slotId }
        val sessionExtras = live.filterNot {
            it.slotId == current.slotId || it.slotId in baselineIds
        }
        val target = listOf(current) + afterCurrentInBaseline + sessionExtras
        live = target
        baseline = target
        persistBaseline()
        return true
    }

    private fun persistBaseline() = baselineWriter.writeBaselineOrder(baseline)
}
