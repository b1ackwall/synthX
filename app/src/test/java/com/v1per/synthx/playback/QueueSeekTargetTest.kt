package com.v1per.synthx.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P4-T8 / R3-F04: the pure accept/reject decision behind
 * [ExoPlayerEngineQueueOps.seekToQueueItemInternal] — [resolveUpcomingSeekTarget] is exactly the
 * production logic, just split out so it's testable without a real `MediaController`, matching
 * the `nextIndexInOrder` precedent for this kind of extraction. `seekToQueueItemInternal` itself
 * (the `ExoPlayerEngine` extension) is not exercised here for the same reason as the rest of this
 * module's engine-layer tests — see [CursorCheckpointTest]'s header note — but it is now a thin
 * wrapper over this function plus [QueueOrderStore.indexOfSlot] (itself covered by
 * [QueueOrderStoreTest]), so nothing production-meaningful is left untested by extracting it.
 */
class QueueSeekTargetTest {

    @Test fun ordinaryTapResolvesToTheTappedSlotsPosition() {
        assertEquals(5, resolveUpcomingSeekTarget(currentIndex = 2, slotIndex = 5))
    }

    /**
     * The acceptance-criteria scenario: a slot id that no longer exists in the current order
     * (simulating a deletion or transition landing between tap and execution) resolves to -1
     * via `indexOfSlot`, and must be rejected — never silently applied to whatever now occupies
     * some assumed position.
     */
    @Test fun aSlotThatNoLongerExistsIsRejected() {
        assertNull(resolveUpcomingSeekTarget(currentIndex = 2, slotIndex = -1))
    }

    @Test fun theCurrentlyPlayingSlotItselfIsRejected() {
        assertNull(resolveUpcomingSeekTarget(currentIndex = 3, slotIndex = 3))
    }

    @Test fun aSlotBeforeTheCurrentOneIsRejected() {
        assertNull(resolveUpcomingSeekTarget(currentIndex = 3, slotIndex = 1))
    }

    @Test fun noCurrentSlotAtAllIsRejected() {
        assertNull(resolveUpcomingSeekTarget(currentIndex = -1, slotIndex = 5))
    }
}
