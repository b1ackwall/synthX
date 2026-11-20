package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The engine-owned baseline writer: [QueueOrderStore] hands it the baseline as it always does, and
 * the baseline only reaches storage after the live order, so the live commit is always first.
 */
class DeferredBaselineWriterTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun r(id: String) = QueueSlotRecord(id, "song-$id")

    private val writer = DeferredBaselineWriter()
    private val store = QueueOrderStore(writer)
    private val log = mutableListOf<String>()

    private fun flush() = writer.flushAfter(
        writeLive = { log += "live" },
        writeBaseline = { records -> log += "baseline:${records.map { it.slotId }}" },
    )

    @Test fun aMirroredEditWritesTheLiveOrderFirstAndTheBaselineSecond() {
        store.captureFresh(listOf(r("a"), r("b")), writeBaseline = false)
        store.insertAfterCurrent(r("x"), currentSlotId = "a", mirrorToBaseline = true)
        assertEquals(emptyList(), log, "nothing reaches storage from the store itself")

        flush()

        assertEquals(listOf("live", "baseline:[a, x, b]"), log)
    }

    @Test fun anEditThatLeavesTheBaselineAloneWritesOnlyTheLiveOrder() {
        store.captureFresh(listOf(r("a"), r("b")), writeBaseline = false)
        store.insertAfterCurrent(r("x"), currentSlotId = "a", mirrorToBaseline = false)

        flush()

        assertEquals(listOf("live"), log)
    }

    @Test fun aBaselineIsWrittenOnceAndOnlyTheLatestOne() {
        store.captureFresh(listOf(r("a"), r("b"), r("c")), writeBaseline = false)
        store.remove("b")
        store.append(r("y"), mirrorToBaseline = true)

        flush()
        flush()

        assertEquals(listOf("live", "baseline:[a, c, y]", "live"), log)
    }

    @Test fun aDiscardedBaselineIsNeverWritten() {
        store.captureFresh(listOf(r("a"), r("b")), writeBaseline = false)
        store.remove("b")

        writer.discard()
        flush()

        assertEquals(listOf("live"), log)
    }
}
