package com.v1per.synthx.data

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The waiter lifecycle behind SongRepositoryImpl.finishSongDeletion: it registers before the
 * deletion notice is emitted and always releases the waiter afterwards, so an acknowledgment given
 * inside the emit is never lost and a timed-out wait leaves no entry behind.
 */
class SongDeletionHandoffTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    // The acknowledgment object is a process-wide singleton: detach exactly what each test attached.
    @AfterTest fun detachEngine() {
        SongDeletionAcknowledgment.engineDetached()
    }

    private fun attachedEngine() = SongDeletionAcknowledgment.engineAttached()

    @Test fun anAcknowledgmentBeforeRegisterIsDropped() {
        attachedEngine()
        SongDeletionAcknowledgment.signalCleared("early")

        val waiter = SongDeletionAcknowledgment.register("early")!!

        assertFalse(waiter.isCompleted, "an acknowledgment with no waiter is not remembered")
        SongDeletionAcknowledgment.unregister("early", waiter)
    }

    @Test fun anAcknowledgmentAfterRegisterCompletesTheWaiterAndClearsTheEntry() {
        attachedEngine()
        val waiter = SongDeletionAcknowledgment.register("ordered")!!

        SongDeletionAcknowledgment.signalCleared("ordered")

        assertTrue(waiter.isCompleted)
        assertFalse(SongDeletionAcknowledgment.hasWaiter("ordered"))
    }

    @Test fun unregisterClearsTheEntryAndALaterRegisterGetsAFreshWaiter() {
        attachedEngine()
        val first = SongDeletionAcknowledgment.register("fresh")!!

        SongDeletionAcknowledgment.unregister("fresh", first)
        assertFalse(SongDeletionAcknowledgment.hasWaiter("fresh"))
        val second = SongDeletionAcknowledgment.register("fresh")!!
        SongDeletionAcknowledgment.signalCleared("fresh")

        assertNotSame(first, second)
        assertTrue(second.isCompleted)
        assertFalse(first.isCompleted, "the released waiter is not completed by a later signal")
    }

    @Test fun aTimedOutWaitLeavesNoEntryOnceUnregistered() = runBlocking {
        attachedEngine()
        val waiter = SongDeletionAcknowledgment.register("timeout")!!

        assertNull(withTimeoutOrNull(50) { waiter.await() })
        assertTrue(SongDeletionAcknowledgment.hasWaiter("timeout"), "the entry stays until it is released")
        SongDeletionAcknowledgment.unregister("timeout", waiter)

        assertFalse(SongDeletionAcknowledgment.hasWaiter("timeout"))
    }

    @Test fun unregisterLeavesAnotherSongsEntryAlone() {
        attachedEngine()
        val mine = SongDeletionAcknowledgment.register("mine")!!
        val other = SongDeletionAcknowledgment.register("other")!!

        SongDeletionAcknowledgment.unregister("mine", mine)

        assertFalse(SongDeletionAcknowledgment.hasWaiter("mine"))
        assertTrue(SongDeletionAcknowledgment.hasWaiter("other"))
        SongDeletionAcknowledgment.unregister("other", other)
    }

    @Test fun unregisterLeavesANewerWaiterForTheSameSongAlone() {
        attachedEngine()
        val stale = SongDeletionAcknowledgment.register("same")!!
        SongDeletionAcknowledgment.signalCleared("same")
        val newer = SongDeletionAcknowledgment.register("same")!!

        SongDeletionAcknowledgment.unregister("same", stale)

        assertTrue(SongDeletionAcknowledgment.hasWaiter("same"), "a waiter other than the released one stays")
        SongDeletionAcknowledgment.signalCleared("same")
        assertTrue(newer.isCompleted)
    }

    @Test fun twoRegistrationsOfTheSameSongShareOneWaiterThatOneSignalCompletes() {
        attachedEngine()
        val first = SongDeletionAcknowledgment.register("shared")!!
        val second = SongDeletionAcknowledgment.register("shared")!!

        SongDeletionAcknowledgment.signalCleared("shared")

        assertSame(first, second)
        assertEquals(true, first.isCompleted)
    }
}
