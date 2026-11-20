package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueExtraKind
import com.v1per.synthx.data.repository.QueueExtraTag
import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The accepted outcome of a process death between the two commits of a rescan remap that gives a
 * shuffled album song a new id: the live commit (new id) lands, the baseline commit (old id) does
 * not, and the old row is flagged missing, so the baseline read drops that slot. Every song stays
 * queued and the live order is the post-edit one; only the slot's album place is lost:
 *  - not the current song: it comes back as an Add to Queue extra and goes last on shuffle OFF;
 *  - the current song: the baseline is rebuilt from the shuffled live order.
 * docs/queue-accepted-behaviours.md records why this is accepted.
 */
class QueueRemapKillOutcomeTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun r(slot: String, song: String = slot) = QueueSlotRecord(slot, song)

    private val s1 = r("s1")
    private val s2 = r("s2", "t2-new")
    private val s3 = r("s3")
    private val s4 = r("s4")
    private val s5 = r("s5")

    /** The baseline as the restore reads it after the kill: s2 still names the missing old id. */
    private val baselineAsRead = listOf(s1, s3, s4, s5)

    private fun restoreAndTurnShuffleOff(
        live: List<QueueSlotRecord>,
        baseline: List<QueueSlotRecord>,
        current: String,
    ): Pair<RepairedQueue, List<String>> {
        val repaired = repairRestoredQueue(live, baseline, emptyMap(), current, true, false)
        val store = QueueOrderStore {}
        store.restore(live, repaired.baseline, repaired.extras, repaired.shuffleReordered)
        check(store.unshuffle(current, RepeatMode.Off, writeBaseline = false))
        return repaired to store.live.map { it.slotId }
    }

    @Test fun aRemappedSongThatIsNotPlayingComesBackAsAnAddToQueueExtra() {
        val live = listOf(s1, s4, s2, s5, s3)

        val (repaired, shuffleOff) = restoreAndTurnShuffleOff(live, baselineAsRead, "s1")

        assertEquals(baselineAsRead, repaired.baseline)
        assertEquals(mapOf("s2" to QueueExtraTag(QueueExtraKind.AddToQueue, 0)), repaired.extras)
        assertEquals(listOf("s1", "s3", "s4", "s5", "s2"), shuffleOff, "s2 is kept but goes last")

        val (_, postEdit) = restoreAndTurnShuffleOff(live, listOf(s1, s2, s3, s4, s5), "s1")
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), postEdit, "with both commits s2 keeps its place")
    }

    @Test fun aRemappedPlayingSongRebuildsTheBaselineFromTheShuffledOrder() {
        val live = listOf(s4, s2, s1, s5, s3)

        val (repaired, shuffleOff) = restoreAndTurnShuffleOff(live, baselineAsRead, "s2")

        assertEquals(live, repaired.baseline, "the album order is replaced by the shuffled live order")
        assertEquals(emptyMap(), repaired.extras)
        assertEquals(listOf("s4", "s2", "s1", "s5", "s3"), shuffleOff, "shuffle OFF keeps the shuffled order")

        val (_, postEdit) = restoreAndTurnShuffleOff(live, listOf(s1, s2, s3, s4, s5), "s2")
        assertEquals(listOf("s4", "s2", "s3", "s5", "s1"), postEdit, "with both commits the album order returns")
    }
}
