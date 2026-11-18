package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
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
 * This is the inversion the windowed player is built on: this store, not the ExoPlayer timeline,
 * is the source of truth, so not every song in the queue has to exist as a live `MediaItem`.
 * The timeline holds a bounded window into this order (see `ExoPlayerEngineWindow.kt`); slot ids
 * are what correlate the two, so an entry keeps its identity whether or not it is currently
 * loaded into the player.
 *
 * Two orders are held:
 *  - [live] — the order that plays, shuffled or not. The slots before the
 *    current one are its history, the slots after it are pending.
 *  - [baseline] — the unshuffled reference order shuffle OFF restores toward. While shuffle is off
 *    it equals [live]; shuffle ON takes it from [live] at the moment of the tap.
 *
 * While shuffle is on, Play Next and Add to Queue add session [extras] to [live] only, and a drag
 * sets [shuffleReordered]. The toggle rules live in `QueueShuffleOrder.kt`. Invariants the
 * toggles and edits keep (docs/queue-shuffle-design.md has the full model):
 *  - a toggle keeps the cursor slot and the exact multiset of slots, and with Repeat Off or One
 *    leaves the history untouched; a computed order that would break this changes nothing;
 *  - while shuffle is off, [baseline] equals [live], with no extras and no reorder flag;
 *  - a slot taken out by the user or a library deletion stays out of both orders;
 *  - repeat mode changes never reorder either order;
 *  - baseline is never trusted blindly: shuffle ON takes it from [live], shuffle OFF treats a live
 *    slot outside it as an extra and never brings back a baseline slot that is not in [live].
 *
 * Both are [QueueSlotRecord]s (slot id + song id), deliberately not `MediaItem`s and not full
 * `Song`s: holding the whole order as records is cheap, while media3's per-item
 * `Window`/`Period`/`MediaSourceHolder` objects are what get expensive. Hydration to real
 * `Song`s for the UI happens in the engine, against its `songsBySlotId` cache.
 *
 * Nothing here touches a `MediaController` or the timeline — the engine applies whatever order
 * this produces.
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

    /** The session extras by slot id: slots added with Play Next or Add to Queue while shuffled. */
    var extras: Map<String, QueueExtraTag> = emptyMap()
        private set

    /** True when the user has moved a slot while shuffled, since the last toggle or fresh play. */
    var shuffleReordered: Boolean = false
        private set

    private var nextExtraSequence = 0L

    val size: Int get() = live.size

    val isEmpty: Boolean get() = live.isEmpty()

    /** Position of [slotId] in [live], or -1. Slot id, never song id — duplicates are distinct. */
    fun indexOfSlot(slotId: String?): Int = if (slotId == null) -1 else live.indexOfFirst { it.slotId == slotId }

    fun slotAt(index: Int): QueueSlotRecord? = live.getOrNull(index)

    /**
     * Everything after [currentSlotId] in [live] — the Up Next list, in full. Empty when the
     * slot is unknown.
     */
    fun upcomingFrom(currentSlotId: String?): List<QueueSlotRecord> {
        val index = indexOfSlot(currentSlotId)
        return if (index < 0) emptyList() else live.subList(index + 1, live.size)
    }

    /**
     * A fresh `playQueue` fully replaces both orders, never merges.
     *
     * [writeBaseline] defaults to true; pass false when the caller is
     * about to persist baseline itself as part of a larger atomic write — a fresh
     * play also writes active order/shuffle/cursor together, and letting this method's own
     * narrow baseline write land independently would reopen the exact torn-write window that
     * unification exists to close.
     */
    fun captureFresh(records: List<QueueSlotRecord>, writeBaseline: Boolean = true) {
        live = records
        baseline = records
        clearShuffleSession()
        if (writeBaseline) persistBaseline()
    }

    /**
     * [captureFresh], except [liveOrder] and [baselineOrder] can differ — needed because a
     * fresh play issued while shuffle is already on must actually shuffle the incoming order
     * rather than leaving the flag and the (unshuffled) order disagreeing. [baselineOrder] is
     * the queue exactly as handed in; [liveOrder] is that same set of records, reordered.
     */
    fun captureFreshShuffled(
        liveOrder: List<QueueSlotRecord>,
        baselineOrder: List<QueueSlotRecord>,
        writeBaseline: Boolean = true,
    ) {
        live = liveOrder
        baseline = baselineOrder
        clearShuffleSession()
        if (writeBaseline) persistBaseline()
    }

    /**
     * Both orders come from storage, so this just adopts them, with the saved
     * session [extras] and reorder flag (repaired first by `repairRestoredQueue`). New extras
     * continue the sequence after the highest one restored.
     */
    fun restore(
        live: List<QueueSlotRecord>,
        baseline: List<QueueSlotRecord>,
        extras: Map<String, QueueExtraTag> = emptyMap(),
        shuffleReordered: Boolean = false,
    ) {
        this.live = live
        this.baseline = baseline
        this.extras = extras
        this.shuffleReordered = shuffleReordered
        extras.values.maxOfOrNull { it.sequence }?.let { nextExtraSequence = maxOf(nextExtraSequence, it + 1) }
    }

    /**
     * Play Next. Always inserted into [live] right after the current slot.
     * [mirrorToBaseline] is "shuffle is off": the slot is then woven into baseline right after the
     * current slot too. While shuffle is on it becomes a Play Next extra in [live] only, which
     * shuffle OFF places right after the current slot, newest first.
     */
    fun insertAfterCurrent(record: QueueSlotRecord, currentSlotId: String?, mirrorToBaseline: Boolean) {
        val liveIndex = indexOfSlot(currentSlotId)
        val insertAt = if (liveIndex >= 0) liveIndex + 1 else live.size
        live = live.toMutableList().apply { add(insertAt.coerceIn(0, size), record) }
        if (mirrorToBaseline) {
            val baseIndex = baseline.indexOfFirst { it.slotId == currentSlotId }
            val baseAt = if (baseIndex >= 0) baseIndex + 1 else baseline.size
            baseline = baseline.toMutableList().apply { add(baseAt.coerceIn(0, size), record) }
            persistBaseline()
        } else {
            tagExtra(record.slotId, QueueExtraKind.PlayNext)
        }
    }

    /**
     * Add to Queue — same rule as [insertAfterCurrent], appended. While shuffle is on the slot is an
     * Add to Queue extra, which shuffle OFF places after the album, oldest first.
     */
    fun append(record: QueueSlotRecord, mirrorToBaseline: Boolean) {
        live = live + record
        if (mirrorToBaseline) {
            baseline = baseline + record
            persistBaseline()
        } else {
            tagExtra(record.slotId, QueueExtraKind.AddToQueue)
        }
    }

    private fun tagExtra(slotId: String, kind: QueueExtraKind) {
        extras = extras + (slotId to QueueExtraTag(kind, nextExtraSequence++))
    }

    private fun clearShuffleSession() {
        extras = emptyMap()
        shuffleReordered = false
    }

    /**
     * Moves the Up Next row identified by [slotId]: the slot is resolved
     * against [live] fresh, right here, rather than trusting a caller-supplied
     * source index — a raw display index captured by the UI at drag-start can be stale by
     * the time the command actually executes (an auto-advance, a deletion, or another reorder
     * landing first shifts every row after it), and acting on it blind would move whatever now
     * occupies that position rather than the row the user actually dragged. If [slotId] no
     * longer exists in [live] at all (removed since the drag began), this rejects cleanly —
     * returns `false`, moves nothing — rather than falling back to some other occurrence. A
     * slot already at [toDisplayIndex] also returns `false`, with both orders untouched.
     *
     * [toDisplayIndex] is still a plain positional target, not slot-identified: unlike the
     * *source* (identity is what makes this safe), the destination is inherently "drop it at
     * this position", which has no independent identity of its own to validate against — see
     * the notes on [com.v1per.synthx.ui.components.QueueList] for the accepted
     * tradeoff this implies.
     *
     * While shuffle is off ([mirrorToBaseline]) the same slot moves in baseline, by identity: it
     * lands right after the slot that precedes it in [live], so equal orders stay equal. While
     * shuffle is on only [live] changes and [shuffleReordered] is set.
     */
    fun moveUpcoming(slotId: String, toDisplayIndex: Int, currentSlotId: String?, mirrorToBaseline: Boolean): Boolean {
        val currentIndex = indexOfSlot(currentSlotId)
        if (currentIndex < 0) return false
        val from = indexOfSlot(slotId)
        // Must resolve to a genuine upcoming-list position — after the current slot, never at
        // or before it (the currently-playing slot itself is never one of the Up Next rows).
        if (from <= currentIndex) return false
        val to = currentIndex + 1 + toDisplayIndex
        if (to !in live.indices) return false
        // Already there: report no move, so the caller skips its structural-change work.
        if (from == to) return false
        live = live.toMutableList().apply { add(to, removeAt(from)) }
        if (mirrorToBaseline) {
            mirrorMoveIntoBaseline(slotId, precedingSlotId = live[to - 1].slotId)
        } else {
            shuffleReordered = true
        }
        return true
    }

    private fun mirrorMoveIntoBaseline(slotId: String, precedingSlotId: String) {
        val from = baseline.indexOfFirst { it.slotId == slotId }
        if (from < 0) return
        val moved = baseline[from]
        val rest = baseline.toMutableList().apply { removeAt(from) }
        val after = rest.indexOfFirst { it.slotId == precedingSlotId }
        if (after < 0) return
        rest.add(after + 1, moved)
        baseline = rest
        persistBaseline()
    }

    /**
     * Always permanent, regardless of shuffle state — removing a slot means
     * "do not play this occurrence again", not "hide it while shuffled", so the exact slot is
     * stripped from both orders, along with its extra tag. Returns false when nothing matched.
     */
    fun remove(slotId: String): Boolean {
        val newLive = live.filterNot { it.slotId == slotId }
        val newBaseline = baseline.filterNot { it.slotId == slotId }
        if (newLive.size == live.size && newBaseline.size == baseline.size) return false
        live = newLive
        extras = extras - slotId
        if (newBaseline.size != baseline.size) {
            baseline = newBaseline
            persistBaseline()
        }
        return true
    }

    /**
     * Batch counterpart of [remove]: removes every slot id in [slotIds] in a
     * single pass over [live]/[baseline] and, if baseline changed at all, exactly one
     * [persistBaseline] call — rather than the O(N) full-list copies and O(N) whole-baseline
     * rewrites that calling [remove] once per id produces for N occurrences of the same deleted
     * song. Returns false when none of [slotIds] matched anything.
     */
    fun removeAll(slotIds: Collection<String>): Boolean {
        if (slotIds.isEmpty()) return false
        val toRemove = if (slotIds is Set<String>) slotIds else slotIds.toHashSet()
        val newLive = live.filterNot { it.slotId in toRemove }
        val newBaseline = baseline.filterNot { it.slotId in toRemove }
        if (newLive.size == live.size && newBaseline.size == baseline.size) return false
        live = newLive
        if (extras.keys.any { it in toRemove }) extras = extras - toRemove
        if (newBaseline.size != baseline.size) {
            baseline = newBaseline
            persistBaseline()
        }
        return true
    }

    /**
     * A rescan can replace one content id while several independently keyed
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
     * Shuffle ON for the cursor [currentSlotId]: baseline becomes the current [live] order, then [live]
     * is reordered by [shuffleOnOrder] — only the pending slots under Repeat Off and One, every slot
     * but the current one under Repeat All. Extras and [shuffleReordered] start empty. Baseline is
     * not written here: the caller's unified write saves it with the rest of the toggle.
     *
     * [shuffler] is injectable purely so tests can pin the permutation; production always uses
     * `shuffled()`. Returns false, changing nothing, when the current slot is unknown or the
     * shuffled order would not hold exactly the same slots.
     */
    fun shuffle(
        currentSlotId: String?,
        repeatMode: RepeatMode = RepeatMode.Off,
        shuffler: (List<QueueSlotRecord>) -> List<QueueSlotRecord> = { it.shuffled() },
    ): Boolean {
        val currentIndex = indexOfSlot(currentSlotId)
        if (currentSlotId == null || currentIndex < 0) return false
        val target = shuffleOnOrder(live, currentIndex, repeatMode.replaysHistoryOnShuffle(), shuffler)
        if (!sameSlotsSameCursor(live, target, currentSlotId)) return false
        baseline = live
        live = target
        clearShuffleSession()
        return true
    }

    /**
     * Shuffle OFF for the cursor [currentSlotId]: [live] is rebuilt by [shuffleOffOrder] — history
     * kept under Repeat Off and One, Play Next extras right after the current slot, the album in
     * baseline order from the current slot, Add to Queue extras last — and becomes the baseline too.
     * A live slot missing from baseline is treated as an extra ([effectiveExtras]); a baseline slot
     * missing from [live] is not brought back. Every extra becomes an ordinary slot and
     * [shuffleReordered] is cleared.
     *
     * [writeBaseline] — see [captureFresh]'s kdoc for why a caller doing its own unified atomic
     * write passes false here instead of letting this method's own narrow write
     * land independently. Returns false, changing nothing, when the current slot is unknown or the
     * new order would not hold exactly the same slots.
     */
    fun unshuffle(
        currentSlotId: String?,
        repeatMode: RepeatMode = RepeatMode.Off,
        writeBaseline: Boolean = true,
    ): Boolean {
        val currentIndex = indexOfSlot(currentSlotId)
        if (currentSlotId == null || currentIndex < 0) return false
        val target = shuffleOffOrder(
            live = live,
            baseline = baseline,
            extras = effectiveExtras(live, baseline, extras),
            currentIndex = currentIndex,
            repeatAll = repeatMode.replaysHistoryOnShuffle(),
        )
        if (!sameSlotsSameCursor(live, target, currentSlotId)) return false
        live = target
        baseline = target
        clearShuffleSession()
        if (writeBaseline) persistBaseline()
        return true
    }

    private fun persistBaseline() = baselineWriter.writeBaselineOrder(baseline)
}
