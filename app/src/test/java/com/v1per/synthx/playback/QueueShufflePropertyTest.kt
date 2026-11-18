package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Random operation sequences over [QueueOrderStore], driven the way the engine drives it, with the
 * invariants of the shuffle design checked after every step. Fixed seeds, so a failure names the
 * seed and the operation log that reproduces it.
 *
 * Shuffle OFF is also checked against an independent model of the rules ([expectedShuffleOff]),
 * which ranks baseline positions with modular arithmetic rather than the production split.
 */
class QueueShufflePropertyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun everyInvariantHoldsAcrossThousandsOfRandomSequences() {
        for (seed in 1L..SEQUENCES) Simulation(seed).run(OPERATIONS_PER_SEQUENCE)
    }

    private class Simulation(private val seed: Long) {
        private val random = Random(seed)
        private val shuffler: (List<QueueSlotRecord>) -> List<QueueSlotRecord> = { it.shuffled(random) }
        private var store = QueueOrderStore { }
        private var current: String? = null
        private var shuffleOn = false
        private var repeatMode = RepeatMode.Off
        private val removed = HashSet<String>()
        private var nextSlot = 0
        private val log = mutableListOf<String>()

        fun run(operations: Int) {
            repeat(operations) {
                val op = random.nextInt(100)
                when {
                    store.isEmpty || current == null || op < 6 -> freshPlay()
                    op < 22 -> next()
                    op < 28 -> previous()
                    op < 36 -> removePending()
                    op < 40 -> deleteSongFromLibrary()
                    op < 48 -> reorder()
                    op < 58 -> playNext()
                    op < 65 -> addToQueue()
                    op < 84 -> toggleShuffle()
                    op < 92 -> toggleRepeat()
                    op < 96 -> restoreRoundTrip()
                    else -> damagedRestore()
                }
                checkInvariants()
            }
        }

        private fun newRecord() = QueueSlotRecord("s${nextSlot++}", "song-${random.nextInt(8)}")

        private fun ids(records: List<QueueSlotRecord>) = records.map { it.slotId }

        private fun currentIndex() = store.indexOfSlot(current)

        private fun freshPlay() {
            val records = List(1 + random.nextInt(12)) { newRecord() }
            val start = random.nextInt(records.size)
            when (random.nextInt(3)) {
                0 -> {
                    log += "play ${ids(records)}"
                    store.captureFresh(records)
                    current = records.first().slotId
                    shuffleOn = false
                }

                1 -> {
                    log += "shuffle button ${ids(records)} start $start"
                    store.captureFreshShuffled(freshShuffledOrder(records, start, shuffler), records)
                    current = records[start].slotId
                    shuffleOn = true
                }

                else -> {
                    log += "song tap ${ids(records)} at $start, shuffle $shuffleOn"
                    if (shuffleOn) {
                        store.captureFreshShuffled(freshShuffledOrder(records, start, shuffler), records)
                    } else {
                        store.captureFresh(records)
                    }
                    current = records[start].slotId
                }
            }
            check(store.live.toSet() == records.toSet() && store.live.size == records.size) { "fresh play membership" }
            check(store.extras.isEmpty() && !store.shuffleReordered) { "a fresh play resets extras and the flag" }
            if (shuffleOn) {
                check(store.live.first().slotId == current) { "a shuffled fresh play starts on its start song" }
                check(store.baseline == records) { "a shuffled fresh play keeps the list order as baseline" }
            }
        }

        private fun next() {
            val target = nextIndexInOrder(store.size, currentIndex(), repeatMode) ?: return
            log += "next"
            current = store.slotAt(target)?.slotId
        }

        private fun previous() {
            val index = currentIndex()
            if (index <= 0) return
            log += "previous"
            current = store.slotAt(index - 1)?.slotId
        }

        private fun removePending() {
            val pending = store.upcomingFrom(current)
            if (pending.isEmpty()) return
            val target = pending[random.nextInt(pending.size)].slotId
            log += "remove $target"
            check(store.remove(target)) { "remove $target" }
            removed += target
        }

        /** The engine's deletion path: leave a deleted current slot first, then strip every copy. */
        private fun deleteSongFromLibrary() {
            val songId = store.live[random.nextInt(store.size)].songId
            val affected = store.live.filter { it.songId == songId }.map { it.slotId }
            log += "delete $songId $affected"
            if (current in affected) {
                var index = currentIndex()
                var target: Int? = null
                for (step in 0 until store.size) {
                    val candidate = nextIndexInOrder(store.size, index, repeatMode) ?: break
                    if (store.slotAt(candidate)?.songId != songId) {
                        target = candidate
                        break
                    }
                    index = candidate
                }
                current = target?.let { store.slotAt(it)?.slotId }
            }
            store.removeAll(affected)
            removed += affected
        }

        private fun reorder() {
            val pending = store.upcomingFrom(current)
            if (pending.isEmpty()) return
            val slot = pending[random.nextInt(pending.size)].slotId
            val to = random.nextInt(pending.size)
            val flagBefore = store.shuffleReordered
            log += "move $slot to $to"
            val moved = store.moveUpcoming(slot, to, current, mirrorToBaseline = !shuffleOn)
            if (shuffleOn) {
                check(store.shuffleReordered == (flagBefore || moved)) { "the reorder flag follows a real move" }
            }
        }

        private fun playNext() {
            val record = newRecord()
            log += "play next ${record.slotId}"
            val before = store.extras.values.maxOfOrNull { it.sequence }
            store.insertAfterCurrent(record, current, mirrorToBaseline = !shuffleOn)
            check(store.upcomingFrom(current).first() == record) { "Play Next goes right after the cursor" }
            checkTag(record, QueueExtraKind.PlayNext, before)
        }

        private fun addToQueue() {
            val record = newRecord()
            log += "add to queue ${record.slotId}"
            val before = store.extras.values.maxOfOrNull { it.sequence }
            store.append(record, mirrorToBaseline = !shuffleOn)
            check(store.live.last() == record) { "Add to Queue goes last" }
            checkTag(record, QueueExtraKind.AddToQueue, before)
        }

        private fun checkTag(record: QueueSlotRecord, kind: QueueExtraKind, previousMax: Long?) {
            val tag = store.extras[record.slotId]
            if (!shuffleOn) {
                check(tag == null) { "an edit while unshuffled is not an extra" }
                return
            }
            check(tag?.kind == kind) { "an edit while shuffled is a $kind extra, got $tag" }
            check(previousMax == null || tag!!.sequence > previousMax) { "sequence numbers increase" }
        }

        private fun toggleShuffle() {
            val cursor = current ?: return
            val before = store.live
            val baselineBefore = store.baseline
            val extrasBefore = store.extras
            log += if (shuffleOn) "shuffle off ($repeatMode)" else "shuffle on ($repeatMode)"
            val applied = if (shuffleOn) {
                store.unshuffle(
                    cursor,
                    repeatMode,
                )
            } else {
                store.shuffle(cursor, repeatMode, shuffler)
            }
            check(applied) { "a toggle with a known cursor and a correct shuffler is applied" }

            check(cursor in ids(store.live)) { "I1: the cursor slot is still there" }
            check(before.groupingBy { it }.eachCount() == store.live.groupingBy { it }.eachCount()) {
                "I2: the same slots before and after"
            }
            if (repeatMode != RepeatMode.All) {
                check(before.takeWhile { it.slotId != cursor } == store.live.takeWhile { it.slotId != cursor }) {
                    "I3: history is unchanged"
                }
            }
            if (shuffleOn) {
                val expected = expectedShuffleOff(
                    before,
                    baselineBefore,
                    extrasBefore,
                    cursor,
                    repeatMode == RepeatMode.All,
                )
                check(store.live == expected) { "shuffle off order: expected ${ids(expected)}, got ${ids(store.live)}" }
            } else {
                check(store.baseline == before) { "I8: shuffle on takes its baseline from the live order" }
            }
            check(store.extras.isEmpty() && !store.shuffleReordered) { "a toggle clears extras and the flag" }
            shuffleOn = !shuffleOn
        }

        private fun toggleRepeat() {
            val live = store.live
            val baseline = store.baseline
            repeatMode = RepeatMode.entries[(repeatMode.ordinal + 1) % RepeatMode.entries.size]
            log += "repeat $repeatMode"
            check(store.live == live && store.baseline == baseline) { "I6: repeat never reorders" }
        }

        /** Persist and load back; repair must leave a state these operations produced untouched. */
        private fun restoreRoundTrip() {
            log += "restore"
            val repaired = repairRestoredQueue(
                store.live,
                store.baseline,
                store.extras,
                current,
                shuffleOn,
                store.shuffleReordered,
            )
            check(repaired.baseline == store.baseline) { "repair changed a consistent baseline" }
            check(repaired.extras == store.extras) { "repair changed consistent extras" }
            check(repaired.shuffleReordered == store.shuffleReordered) { "repair changed the flag" }
            store = QueueOrderStore { }.apply {
                restore(store.live, repaired.baseline, repaired.extras, repaired.shuffleReordered)
            }
        }

        /** A torn or stale baseline at restore: the repair must lose no slot and resurrect none. */
        private fun damagedRestore() {
            val live = store.live
            val damaged = when (random.nextInt(4)) {
                0 -> emptyList()
                1 -> store.baseline.filter { random.nextBoolean() }
                2 -> store.baseline + removed.take(2).map { QueueSlotRecord(it, "song-gone") }
                else -> store.baseline.shuffled(random)
            }
            log += "damaged restore baseline=${ids(damaged)}"
            val repaired = repairRestoredQueue(live, damaged, store.extras, current, shuffleOn, store.shuffleReordered)
            store =
                QueueOrderStore {
                }.apply { restore(live, repaired.baseline, repaired.extras, repaired.shuffleReordered) }
            check(store.live == live) { "restore never changes the live order" }
        }

        private fun checkInvariants() {
            val liveIds = ids(store.live)
            check(liveIds.toSet().size == liveIds.size) { "slot ids are unique" }
            current?.let { check(it in liveIds) { "the cursor names a live slot" } }
            check(liveIds.none { it in removed } && store.baseline.none { it.slotId in removed }) {
                "I5: a removed slot never comes back"
            }
            check(store.extras.keys.all { it in liveIds }) { "every extra is in the live order" }
            if (!shuffleOn) {
                check(store.baseline == store.live) { "I4: baseline equals live while unshuffled" }
                check(store.extras.isEmpty() && !store.shuffleReordered) { "no extras or flag while unshuffled" }
            } else {
                val baselineIds = ids(store.baseline).toSet()
                check(baselineIds.all { it in liveIds }) { "baseline holds only live slots" }
                check(liveIds.all { it in baselineIds || it in store.extras }) {
                    "every live slot outside baseline is a tagged extra"
                }
            }
        }

        private fun check(condition: Boolean, message: () -> String) {
            if (condition) return
            val tail = log.takeLast(25).joinToString("\n  ")
            fail(
                "seed $seed: ${message()}\n  live=${ids(store.live)}\n  baseline=${ids(store.baseline)}\n" +
                    "  extras=${store.extras}\n  cursor=$current shuffle=$shuffleOn repeatMode=$repeatMode\n  $tail",
            )
        }
    }

    private companion object {
        const val SEQUENCES = 3_000L
        const val OPERATIONS_PER_SEQUENCE = 60

        /**
         * The shuffle-off rules restated: every slot outside baseline is an extra (an untagged one
         * ranks as Add to Queue after every tagged extra, in live order); the album continues after
         * the anchor in baseline order and wraps; Play Next newest first, Add to Queue oldest first.
         */
        fun expectedShuffleOff(
            live: List<QueueSlotRecord>,
            baseline: List<QueueSlotRecord>,
            extras: Map<String, QueueExtraTag>,
            cursor: String,
            repeatAll: Boolean,
        ): List<QueueSlotRecord> {
            val baseIndex = HashMap<String, Int>()
            baseline.forEachIndexed { index, record -> baseIndex.putIfAbsent(record.slotId, index) }
            val liveTagged = live.mapNotNull { record -> extras[record.slotId]?.let { record.slotId to it } }.toMap()
            var legacySequence = (liveTagged.values.maxOfOrNull { it.sequence } ?: -1L) + 1
            val tags = HashMap<String, QueueExtraTag>(liveTagged)
            live.forEach { record ->
                if (record.slotId !in tags && record.slotId !in baseIndex) {
                    tags[record.slotId] = QueueExtraTag(QueueExtraKind.AddToQueue, legacySequence++)
                }
            }
            val cursorIndex = live.indexOfFirst { it.slotId == cursor }
            val history = live.subList(0, cursorIndex)
            val pending = live.subList(cursorIndex + 1, live.size)
            val isOrigin = { record: QueueSlotRecord -> record.slotId !in tags }
            val anchor = if (isOrigin(live[cursorIndex])) {
                baseIndex.getValue(cursor)
            } else {
                history.lastOrNull(isOrigin)?.let { baseIndex.getValue(it.slotId) } ?: -1
            }
            val size = baseline.size.coerceAtLeast(1)
            val rank = { record: QueueSlotRecord ->
                Math.floorMod(baseIndex.getValue(record.slotId) - anchor - 1, size)
            }
            val playNext = pending.filter { tags[it.slotId]?.kind == QueueExtraKind.PlayNext }
                .sortedByDescending { tags.getValue(it.slotId).sequence }
            val addToQueue = pending.filter { tags[it.slotId]?.kind == QueueExtraKind.AddToQueue }
                .sortedBy { tags.getValue(it.slotId).sequence }
            return if (repeatAll) {
                val heardExtras = history.filterNot(isOrigin)
                val album = (history + pending).filter(isOrigin).sortedBy(rank)
                heardExtras + live[cursorIndex] + playNext + album + addToQueue
            } else {
                history + live[cursorIndex] + playNext + pending.filter(isOrigin).sortedBy(rank) + addToQueue
            }
        }
    }

    @Test fun theIndependentModelAgreesWithTheWorkedExampleForRepeatAll() {
        val album = (1..8).map { QueueSlotRecord("t$it", "song-t$it") }
        val shuffled = listOf(album[3]) + (album - album[3]).reversed()

        val expected = expectedShuffleOff(shuffled, album, emptyMap(), "t4", repeatAll = true)

        assertEquals(listOf("t4", "t5", "t6", "t7", "t8", "t1", "t2", "t3"), expected.map { it.slotId })
        assertTrue(expected.size == album.size)
    }
}
