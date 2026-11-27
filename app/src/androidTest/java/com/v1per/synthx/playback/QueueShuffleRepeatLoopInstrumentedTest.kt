package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real engine under Repeat All with shuffle: Next past the last song goes to the first song of
 * the same shuffled order, and the second loop plays exactly the order of the first. The live order
 * is never reshuffled at the wrap.
 */
@RunWith(AndroidJUnit4::class)
class QueueShuffleRepeatLoopInstrumentedTest : PlaybackServiceTestBase() {

    @Test fun theSecondLoopReplaysTheFirstLoopsShuffledOrder() {
        runBlocking { engine.playQueue(songs(6, "loop"), 0, shuffle = true) }
        await("shuffled queue loaded") { onMain { engine.orderStore.size == 6 && engine._currentSlotId.value != null } }
        onMain { engine.setRepeatMode(RepeatMode.All) }
        await("repeat all") { onMain { engine._repeatMode.value == RepeatMode.All } }
        onMain { engine.pause() }
        settle()
        val order = onMain { engine.orderStore.live.map { it.slotId } }
        assertEquals(order.first(), onMain { engine._currentSlotId.value })

        val played = mutableListOf(order.first())
        repeat(2 * order.size - 1) {
            val before = played.last()
            onMain { engine.next() }
            await("moved on from $before") {
                onMain {
                    val now = engine._currentSlotId.value
                    now != before && extraController.currentMediaItem?.mediaId == now
                }
            }
            played += onMain { engine._currentSlotId.value!! }
        }

        assertEquals("two loops of the same order", order + order, played)
        assertEquals("the live order is never reshuffled", order, onMain { engine.orderStore.live.map { it.slotId } })
    }
}
