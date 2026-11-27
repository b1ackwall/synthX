package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlotRecord
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Repeat All with shuffle replays the same shuffled order on every loop, by design: the shuffled
 * live order is made once (a shuffled fresh play or shuffle ON) and nothing reorders it at the wrap.
 * The next song after the last one is the first of that same order, and the loaded window that
 * carries a long queue across its end continues into the same order's start.
 */
class QueueShuffleRepeatLoopTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun records(count: Int) = List(count) { QueueSlotRecord("slot-$it", "song-$it") }

    /** The slots [loops] whole loops play, from [store]'s first slot, by the engine's own next rule. */
    private fun play(store: QueueOrderStore, loops: Int): List<String> {
        var index = 0
        return List(loops * store.size) {
            val slot = store.slotAt(index)!!.slotId
            index = nextIndexInOrder(store.size, index, RepeatMode.All)!!
            slot
        }
    }

    private fun shuffledStore(count: Int, seed: Int): QueueOrderStore {
        val list = records(count)
        return QueueOrderStore { }.apply {
            captureFreshShuffled(
                freshShuffledOrder(list, startIndex = 0, shuffler = {
                    it.shuffled(Random(seed))
                }),
                list,
            )
        }
    }

    @Test fun everyLoopOfAShuffledQueueReplaysTheSameOrder() {
        for (count in listOf(2, 5, WINDOW_SIZE, WINDOW_SIZE + 9)) {
            val store = shuffledStore(count, seed = count)
            val order = store.live.map { it.slotId }
            // Two songs with a fixed first song have only one order.
            if (count > 2) assertNotEquals(records(count).map { it.slotId }, order, "precondition: shuffled ($count)")

            val played = play(store, loops = 3)

            assertEquals(List(3) { order }.flatten(), played, "three loops of $count songs")
            assertEquals(order, store.live.map { it.slotId }, "the live order is untouched by the wraps")
        }
    }

    @Test fun shuffleOnUnderRepeatAllMakesOneOrderThatEveryLoopKeeps() {
        val store = QueueOrderStore { }.apply { captureFresh(records(8)) }
        store.shuffle("slot-3", RepeatMode.All) { it.shuffled(Random(3)) }
        val order = store.live.map { it.slotId }

        assertEquals(List(2) { order }.flatten(), play(store, loops = 2))
    }

    @Test fun theWindowAcrossTheEndOfALongShuffledQueueContinuesIntoTheSameOrder() {
        val store = shuffledStore(WINDOW_SIZE + 30, seed = 7)
        val last = store.size - 1

        val window = computeWindow(store.live, currentIndex = last, wrapAround = true).map { it.slotId }

        val order = store.live.map { it.slotId }
        val expected = (last - WINDOW_BACK until last + WINDOW_FORWARD + 1).map { order[it % order.size] }
        assertEquals(expected, window)
    }
}
