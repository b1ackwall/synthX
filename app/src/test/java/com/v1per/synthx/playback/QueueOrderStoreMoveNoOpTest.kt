package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [QueueOrderStore.moveUpcoming]'s same-position guard. A `false` result is what keeps
 * `moveQueueItemInternal` from refreshing the window, persisting and marking the queue
 * customised, so a no-op drop must report `false` and leave both orders untouched.
 */
class QueueOrderStoreMoveNoOpTest {

    private class RecordingWriter : BaselineOrderWriter {
        val writes = mutableListOf<List<String>>()
        override fun writeBaselineOrder(records: List<QueueSlotRecord>) {
            writes += records.map { it.slotId }
        }
    }

    private fun records(vararg ids: String) = ids.map { QueueSlotRecord("slot-$it", "song-$it") }

    private fun QueueOrderStore.liveIds() = live.map { it.slotId }

    private fun QueueOrderStore.baselineIds() = baseline.map { it.slotId }

    @Test fun moveToOwnPositionReturnsFalseAndChangesNothing() {
        val writer = RecordingWriter()
        val subject = QueueOrderStore(writer)
        subject.captureFresh(records("a", "b", "c", "d"))
        val writesBefore = writer.writes.size

        // "slot-c" is Up Next row 1 after current "slot-a".
        assertFalse(subject.moveUpcoming("slot-c", toDisplayIndex = 1, currentSlotId = "slot-a", mirrorToBaseline = true))

        assertEquals(listOf("slot-a", "slot-b", "slot-c", "slot-d"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-c", "slot-d"), subject.baselineIds())
        assertEquals(writesBefore, writer.writes.size)
    }

    @Test fun moveToOwnPositionUnderShuffleReturnsFalse() {
        val subject = QueueOrderStore(RecordingWriter())
        subject.captureFresh(records("a", "b", "c"))
        subject.shuffle("slot-a") { it.reversed() }
        val liveBefore = subject.liveIds()
        val ownIndex = liveBefore.indexOf("slot-b") - liveBefore.indexOf("slot-a") - 1

        assertFalse(subject.moveUpcoming("slot-b", toDisplayIndex = ownIndex, currentSlotId = "slot-a", mirrorToBaseline = false))

        assertEquals(liveBefore, subject.liveIds())
    }

    @Test fun moveOneRowStillReturnsTrueAndPersistsBaseline() {
        val writer = RecordingWriter()
        val subject = QueueOrderStore(writer)
        subject.captureFresh(records("a", "b", "c", "d"))
        val writesBefore = writer.writes.size

        assertTrue(subject.moveUpcoming("slot-c", toDisplayIndex = 2, currentSlotId = "slot-a", mirrorToBaseline = true))

        assertEquals(listOf("slot-a", "slot-b", "slot-d", "slot-c"), subject.liveIds())
        assertEquals(listOf("slot-a", "slot-b", "slot-d", "slot-c"), subject.baselineIds())
        assertEquals(writesBefore + 1, writer.writes.size)
    }
}
