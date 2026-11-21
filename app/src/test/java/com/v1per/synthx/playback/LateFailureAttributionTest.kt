package com.v1per.synthx.playback

import androidx.media3.common.PlaybackException
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An error is charged to the slot that raised it. An error for a slot the engine already left (a
 * late duplicate for the song a skip just moved on from) is not a failure of the current song.
 */
class LateFailureAttributionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val code = PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    @Test fun anErrorForASlotTheEngineAlreadyLeftIsLate() {
        assertTrue(isLateErrorForLeftSlot(failedSlotId = "bad", currentSlotId = "good"))
    }

    @Test fun anErrorForTheCurrentSlotIsGenuine() {
        assertFalse(isLateErrorForLeftSlot(failedSlotId = "bad", currentSlotId = "bad"))
    }

    @Test fun anErrorThatDoesNotNameItsSlotIsGenuine() {
        assertFalse(isLateErrorForLeftSlot(failedSlotId = null, currentSlotId = "good"))
    }

    @Test fun anErrorWithNoCurrentSlotIsGenuine() {
        assertFalse(isLateErrorForLeftSlot(failedSlotId = "bad", currentSlotId = null))
        assertFalse(isLateErrorForLeftSlot(failedSlotId = null, currentSlotId = null))
    }

    /** One error as the engine sees it: the slot that raised it and the slot the engine is on. */
    private class Report(val failed: String?, val current: String?)

    /** Mirrors the engine: late errors are dropped, the others count and consult [playbackFailureActionFor]. */
    private fun run(repeat: RepeatMode, queueLength: Int, reports: List<Report>): List<PlaybackFailureAction?> {
        var failures = 0
        return reports.map { report ->
            if (isLateErrorForLeftSlot(report.failed, report.current)) {
                null
            } else {
                failures++
                playbackFailureActionFor(code, repeat, true, failures, queueLength, playerIdle = true)
            }
        }
    }

    @Test fun aLateDuplicateAfterASkipIsNotCountedAndRepeatOneKeepsItsRoom() {
        // [good, bad] under Repeat One: the bad song fails, the skip lands on "good", then the same
        // failure arrives again. Counted, it would reach the limit of 2 and stop on "good".
        val actions = run(
            RepeatMode.One,
            queueLength = 2,
            reports = listOf(Report("bad", "bad"), Report("bad", "good")),
        )
        assertEquals(listOf(PlaybackFailureAction.SKIP, null), actions)
    }

    @Test fun twoBadSongsInARowStillCountOneThenTwo() {
        val actions = run(
            RepeatMode.Off,
            queueLength = 5,
            reports = listOf(Report("a", "a"), Report("b", "b")),
        )
        assertEquals(listOf(PlaybackFailureAction.SKIP, PlaybackFailureAction.SKIP), actions)
        // Under the wrapping limit of 2 the second genuine failure ends the burst.
        val wrapped = run(
            RepeatMode.One,
            queueLength = 2,
            reports = listOf(Report("a", "a"), Report("b", "b")),
        )
        assertEquals(listOf(PlaybackFailureAction.SKIP, PlaybackFailureAction.STAY), wrapped)
    }

    @Test fun theCapOfFiveStillEndsABurstOfGenuineFailures() {
        val reports = (0 until 5).map { Report("s$it", "s$it") }
        val actions = run(RepeatMode.Off, queueLength = 20, reports = reports)
        assertEquals(List(4) { PlaybackFailureAction.SKIP } + PlaybackFailureAction.STAY, actions)
    }

    @Test fun lateDuplicatesBetweenGenuineFailuresDoNotMoveTheCap() {
        val reports = buildList {
            for (i in 0 until 5) {
                add(Report("s$i", "s$i"))
                add(Report("s$i", "s${i + 1}"))
            }
        }
        val actions = run(RepeatMode.Off, queueLength = 20, reports = reports)
        val genuine = actions.filterNotNull()
        assertEquals(List(4) { PlaybackFailureAction.SKIP } + PlaybackFailureAction.STAY, genuine)
        assertEquals(5, actions.count { it == null })
    }
}
