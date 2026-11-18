package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlotRecord

// The pure order rules behind shuffle ON and OFF and behind a restored queue's repair. QueueOrderStore
// applies them; docs/queue-shuffle-design.md has the full rules and the worked examples.
//
// Vocabulary: live is the order that plays; the cursor is the playing slot; history is every slot
// before the cursor in live (position decides, nothing else); pending is every slot after it;
// baseline is the unshuffled reference order; an extra is a slot added with Play Next or Add to
// Queue while shuffle is on, in live only; a baseline-origin slot is one in baseline that is not an
// extra. Every function here keeps slot identity: a slot is never dropped, duplicated or invented.

/** Shuffle ON and OFF bring the history into play only under Repeat All; Repeat One and Off keep it. */
internal fun RepeatMode.replaysHistoryOnShuffle(): Boolean = this == RepeatMode.All

/**
 * Shuffle ON's new live order for the cursor at [currentIndex]. Without [repeatAll] the history and
 * the current slot keep their place and only the pending slots go through [shuffler]; with it the
 * current slot leads and every other slot, history included, goes through [shuffler].
 */
internal fun shuffleOnOrder(
    live: List<QueueSlotRecord>,
    currentIndex: Int,
    repeatAll: Boolean,
    shuffler: (List<QueueSlotRecord>) -> List<QueueSlotRecord>,
): List<QueueSlotRecord> = if (repeatAll) {
    listOf(live[currentIndex]) + shuffler(live.filterIndexed { index, _ -> index != currentIndex })
} else {
    live.subList(0, currentIndex + 1) + shuffler(live.subList(currentIndex + 1, live.size).toList())
}

/**
 * Shuffle OFF's new live order for the cursor at [currentIndex]. [extras] must come from
 * [effectiveExtras], so that every live slot outside [baseline] is an extra.
 *
 * The album continues from the anchor: the current slot's baseline position when it is
 * baseline-origin, otherwise that of the nearest baseline-origin slot before it in live, otherwise
 * -1. Baseline-origin slots follow in baseline order from just after the anchor, then wrap to the
 * ones at or before it.
 *  - Without [repeatAll]: history unchanged, the current slot, pending Play Next extras newest
 *    first, the pending baseline-origin slots from the anchor, pending Add to Queue extras oldest
 *    first.
 *  - With [repeatAll]: the extras in the history in their order, the current slot, pending Play
 *    Next, every baseline-origin slot but the current one from the anchor, pending Add to Queue.
 *
 * The shuffled order of the baseline-origin slots is never read, so reorders made while shuffled
 * are discarded; baseline slots that are not in live are never brought back.
 */
internal fun shuffleOffOrder(
    live: List<QueueSlotRecord>,
    baseline: List<QueueSlotRecord>,
    extras: Map<String, QueueExtraTag>,
    currentIndex: Int,
    repeatAll: Boolean,
): List<QueueSlotRecord> {
    val isExtra = { record: QueueSlotRecord -> record.slotId in extras }
    val current = live[currentIndex]
    val history = live.subList(0, currentIndex)
    val pending = live.subList(currentIndex + 1, live.size)
    val baselineIndex = HashMap<String, Int>(baseline.size * 2)
    baseline.forEachIndexed { index, record -> baselineIndex.putIfAbsent(record.slotId, index) }
    val anchorSlot = if (isExtra(current)) history.lastOrNull { !isExtra(it) } else current
    val anchor = anchorSlot?.let { baselineIndex.getValue(it.slotId) } ?: -1

    val playNext = pending.filter { extras[it.slotId]?.kind == QueueExtraKind.PlayNext }
        .sortedByDescending { extras.getValue(it.slotId).sequence }
    val addToQueue = pending.filter { extras[it.slotId]?.kind == QueueExtraKind.AddToQueue }
        .sortedBy { extras.getValue(it.slotId).sequence }
    val album = if (repeatAll) (history + pending).filterNot(isExtra) else pending.filterNot(isExtra)
    val lead = if (repeatAll) history.filter(isExtra) else history
    return lead + current + playNext + inBaselineOrderFrom(album, baseline, anchor) + addToQueue
}

/**
 * [slots] (live records, every one of them in [baseline]) in baseline order starting just after
 * [anchor], then wrapping to the ones at or before it. A single pass over baseline, no sort. A slot
 * not found in baseline is left out, so the toggle guard refuses the order instead of guessing.
 */
private fun inBaselineOrderFrom(
    slots: List<QueueSlotRecord>,
    baseline: List<QueueSlotRecord>,
    anchor: Int,
): List<QueueSlotRecord> {
    val wanted = slots.associateByTo(HashMap(slots.size * 2)) { it.slotId }
    val ordered = ArrayList<QueueSlotRecord>(slots.size)
    for (index in anchor + 1 until baseline.size) wanted.remove(baseline[index].slotId)?.let(ordered::add)
    for (index in 0..anchor) wanted.remove(baseline[index].slotId)?.let(ordered::add)
    return ordered
}

/**
 * The extras shuffle OFF and a restore work with: the tags in [extras] of slots still in [live],
 * plus every untagged live slot missing from [baseline], tagged Add to Queue after the highest
 * remaining sequence, in live order. Such a slot is never dropped and never treated as album.
 */
internal fun effectiveExtras(
    live: List<QueueSlotRecord>,
    baseline: List<QueueSlotRecord>,
    extras: Map<String, QueueExtraTag>,
): Map<String, QueueExtraTag> {
    val baselineIds = baseline.mapTo(HashSet(baseline.size * 2)) { it.slotId }
    val result = LinkedHashMap<String, QueueExtraTag>()
    live.forEach { record -> extras[record.slotId]?.let { result[record.slotId] = it } }
    var nextSequence = (result.values.maxOfOrNull { it.sequence } ?: -1L) + 1
    live.forEach { record ->
        if (record.slotId !in result && record.slotId !in baselineIds) {
            result[record.slotId] = QueueExtraTag(QueueExtraKind.AddToQueue, nextSequence++)
        }
    }
    return result
}

/**
 * The fail-closed check every toggle runs on its computed order: [after] holds exactly the slots of
 * [before] (as a multiset of records) and still holds the cursor [currentSlotId].
 */
internal fun sameSlotsSameCursor(
    before: List<QueueSlotRecord>,
    after: List<QueueSlotRecord>,
    currentSlotId: String,
): Boolean {
    if (before.size != after.size || after.none { it.slotId == currentSlotId }) return false
    val counts = HashMap<QueueSlotRecord, Int>(before.size * 2)
    before.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    after.forEach { record ->
        val count = counts[record] ?: return false
        if (count == 1) counts.remove(record) else counts[record] = count - 1
    }
    return counts.isEmpty()
}

/** A fresh shuffled play: the slot at [startIndex] first, the rest of [records] through [shuffler]. */
internal fun freshShuffledOrder(
    records: List<QueueSlotRecord>,
    startIndex: Int,
    shuffler: (List<QueueSlotRecord>) -> List<QueueSlotRecord> = { it.shuffled() },
): List<QueueSlotRecord> {
    val start = records[startIndex]
    return listOf(start) + shuffler(records - start)
}

/** What a restored queue's baseline, extras and reorder flag become; see [repairRestoredQueue]. */
internal data class RepairedQueue(
    val baseline: List<QueueSlotRecord>,
    val extras: Map<String, QueueExtraTag>,
    val shuffleReordered: Boolean,
)

/**
 * Makes a restored queue safe for shuffle OFF without touching its [live] order:
 *  - shuffle off: baseline is the live order, with no extras and no reorder flag;
 *  - shuffle on: tags of slots outside [live] and baseline slots outside [live] are dropped; a
 *    baseline that is empty, shares no slot with [live], or lacks an untagged current slot is
 *    rebuilt from [live] without its tagged extras; any other untagged live slot outside baseline
 *    becomes an Add to Queue extra (see [effectiveExtras]); the reorder flag is kept.
 * A tagged extra as the current slot is legitimately outside baseline and does not trigger the
 * rebuild. A queue these rules produced comes back unchanged.
 */
internal fun repairRestoredQueue(
    live: List<QueueSlotRecord>,
    baseline: List<QueueSlotRecord>,
    extras: Map<String, QueueExtraTag>,
    currentSlotId: String?,
    shuffleEnabled: Boolean,
    shuffleReordered: Boolean,
): RepairedQueue {
    if (!shuffleEnabled) return RepairedQueue(live, emptyMap(), shuffleReordered = false)
    val liveIds = live.mapTo(HashSet(live.size * 2)) { it.slotId }
    val tagged = extras.filterKeys { it in liveIds }
    val baselineInLive = baseline.filter { it.slotId in liveIds }
    val currentMissing = currentSlotId != null && currentSlotId !in tagged &&
        baselineInLive.none { it.slotId == currentSlotId }
    val repairedBaseline = if (baselineInLive.isEmpty() || currentMissing) {
        live.filterNot { it.slotId in tagged }
    } else {
        baselineInLive
    }
    return RepairedQueue(repairedBaseline, effectiveExtras(live, repairedBaseline, tagged), shuffleReordered)
}
