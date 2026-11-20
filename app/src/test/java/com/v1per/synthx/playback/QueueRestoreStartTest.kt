package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a restored queue starts ([resolveRestoreStart]): the saved slot at its saved position when
 * it survives, otherwise the first surviving slot at or after the saved queue position, otherwise
 * the last one before it, always at 0 ms. Without a saved position the first survivor starts.
 */
class QueueRestoreStartTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    /** [survivors] are slot id to stored queue position, in stored order. */
    private fun start(
        survivors: List<Pair<String, Int>>,
        currentSlotId: String?,
        currentQueuePosition: Int?,
        savedPositionMs: Long = 9_000L,
    ): RestoreStart? = resolveRestoreStart(
        slotIds = survivors.map { it.first },
        storedPositions = survivors.toMap(),
        currentSlotId = currentSlotId,
        currentQueuePosition = currentQueuePosition,
        savedPositionMs = savedPositionMs,
    )

    private data class Case(
        val name: String,
        val survivors: List<Pair<String, Int>>,
        val currentSlotId: String?,
        val currentQueuePosition: Int?,
        val expected: RestoreStart?,
    )

    private val cases = listOf(
        Case("surviving current slot", listOf("a" to 0, "b" to 1, "c" to 2), "b", 1, RestoreStart(1, 9_000L)),
        Case(
            "surviving current slot whose stored position disagrees",
            listOf("a" to 0, "b" to 1, "c" to 2),
            "b",
            7,
            RestoreStart(1, 9_000L),
        ),
        Case("missing current, flagged row kept", listOf("a" to 0, "c" to 2, "e" to 4), "d", 3, RestoreStart(2, 0L)),
        Case("cascade-deleted current, gap at 2", listOf("a" to 0, "b" to 1, "d" to 3), "c", 2, RestoreStart(2, 0L)),
        Case("missing current at the end", listOf("a" to 0, "b" to 1), "c", 2, RestoreStart(1, 0L)),
        Case("missing current at the start", listOf("b" to 1, "c" to 2), "a", 0, RestoreStart(0, 0L)),
        Case("several missing in a row", listOf("a" to 0, "f" to 5), "c", 2, RestoreStart(1, 0L)),
        Case("legacy cursor without a position", listOf("a" to 0, "c" to 2), "b", null, RestoreStart(0, 0L)),
        Case("no current slot", listOf("a" to 0, "b" to 1), null, null, RestoreStart(0, 0L)),
        Case("no current slot with a stale position", listOf("a" to 0, "b" to 1), null, 1, RestoreStart(1, 0L)),
        Case("shuffled live order", listOf("c" to 0, "a" to 1, "b" to 3, "e" to 4), "d", 2, RestoreStart(2, 0L)),
        Case("no survivors", emptyList(), "a", 0, null),
        Case("no survivors and no position", emptyList(), null, null, null),
    )

    @Test fun everyCaseResolvesAsTheRuleSays() {
        cases.forEach { case ->
            assertEquals(
                case.expected,
                start(case.survivors, case.currentSlotId, case.currentQueuePosition),
                case.name,
            )
        }
    }

    @Test fun aSurvivingCurrentSlotKeepsItsSavedPlaybackPosition() {
        assertEquals(RestoreStart(0, 123L), start(listOf("a" to 0), "a", 0, savedPositionMs = 123L))
    }

    @Test fun aReplacementStartAlwaysBeginsAtZero() {
        assertEquals(0L, start(listOf("a" to 0, "c" to 2), "b", 1, savedPositionMs = 50_000L)?.positionMs)
    }

    @Test fun duplicateSongsAreToldApartBySlot() {
        // Slot identity only: "x2" survives at position 2 and is found, never confused with "x1".
        assertEquals(RestoreStart(1, 9_000L), start(listOf("x1" to 0, "x2" to 2), "x2", 2))
    }

    @Test fun nothingSurvivingRestoresNothing() {
        assertNull(start(emptyList(), "a", 3))
    }
}
