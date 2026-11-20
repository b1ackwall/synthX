package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueCursor
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.QueueSnapshot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Where an adopting engine puts the playing slot whose song is flagged missing: back among the
 * surviving slots at its stored position, from its saved row. Nothing else changes.
 */
class QueueAdoptionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun slot(id: String, missing: Boolean = false) =
        QueueSlot(id, Song("song-$id", id, "Artist", "Album", 1_000L, "file:///$id", isMissing = missing))

    private fun snapshot(
        stored: List<String>,
        missing: List<String>,
        positions: Map<String, Int> = stored.withIndex().associate { (index, id) -> id to index },
    ) = QueueSnapshot(
        slots = stored.filter { it !in missing }.map { slot(it) },
        storedSlotCount = stored.size,
        cursor = QueueCursor("p", 0L, shuffleEnabled = false, repeatMode = 0),
        storedPositions = positions,
        currentQueuePosition = positions["p"],
        missingSlots = missing.map { slot(it, missing = true) },
    )

    private fun QueueSnapshot.ids() = slots.map { it.slotId }

    @Test fun theMissingPlayingSlotGoesBackAtItsStoredPosition() {
        val adopted = snapshot(listOf("a", "b", "p", "c", "d"), missing = listOf("p")).withPlayingSlotAdopted("p")

        assertEquals(listOf("a", "b", "p", "c", "d"), adopted.ids())
        assertEquals(true, adopted.slots[2].song.isMissing)
        assertEquals(emptyList(), adopted.missingSlots.map { it.slotId })
    }

    @Test fun itGoesFirstOrLastWhenStoredThere() {
        assertEquals(
            listOf("p", "a", "b"),
            snapshot(listOf("p", "a", "b"), missing = listOf("p")).withPlayingSlotAdopted("p").ids(),
        )
        assertEquals(
            listOf("a", "b", "p"),
            snapshot(listOf("a", "b", "p"), missing = listOf("p")).withPlayingSlotAdopted("p").ids(),
        )
    }

    @Test fun storedPositionsWithGapsStillPlaceItBetweenItsNeighbours() {
        val adopted = snapshot(
            stored = listOf("a", "b", "p", "c"),
            missing = listOf("p"),
            positions = mapOf("a" to 0, "b" to 4, "p" to 7, "c" to 9),
        ).withPlayingSlotAdopted("p")

        assertEquals(listOf("a", "b", "p", "c"), adopted.ids())
    }

    @Test fun withoutAStoredPositionItGoesFirst() {
        val adopted = snapshot(
            stored = listOf("a", "p", "b"),
            missing = listOf("p"),
            positions = mapOf("a" to 0, "b" to 2),
        ).withPlayingSlotAdopted("p")

        assertEquals(listOf("p", "a", "b"), adopted.ids())
    }

    @Test fun itIsTheOnlySlotWhenNothingElseSurvives() {
        assertEquals(listOf("p"), snapshot(listOf("p"), missing = listOf("p")).withPlayingSlotAdopted("p").ids())
    }

    @Test fun onlyThePlayingSlotIsAdoptedAndOtherMissingSlotsStayDropped() {
        val adopted = snapshot(listOf("a", "x", "p", "b"), missing = listOf("x", "p")).withPlayingSlotAdopted("p")

        assertEquals(listOf("a", "p", "b"), adopted.ids())
        assertEquals(listOf("x"), adopted.missingSlots.map { it.slotId })
    }

    @Test fun aPlayingSlotThatSurvivesOrHasNoRowChangesNothing() {
        val surviving = snapshot(listOf("a", "p", "b"), missing = listOf("b"))
        assertSame(surviving, surviving.withPlayingSlotAdopted("p"))

        val rowGone = snapshot(listOf("a", "b"), missing = emptyList())
        assertSame(rowGone, rowGone.withPlayingSlotAdopted("p"))
        assertSame(rowGone, rowGone.withPlayingSlotAdopted(null))
    }
}
