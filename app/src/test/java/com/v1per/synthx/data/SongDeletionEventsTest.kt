package com.v1per.synthx.data

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongDeletionEventsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun everyPublishedDeletionCarriesAFreshIncreasingRevision() = runBlocking {
        val collector = async { SongDeletionEvents.events.take(2).toList() }
        yield() // let the collector subscribe before anything is published

        SongDeletionEvents.notifyDeleted("song-1")
        SongDeletionEvents.notifyDeleted("song-2")
        val collected = withTimeout(5_000) { collector.await() }

        assertTrue(collected[1].revision > collected[0].revision)
    }
}

/**
 * The handoff barrier behind SongRepositoryImpl.finishSongDeletion's deletion-vs-file-delete race
 * guard: a live engine must be given a chance to react before the file is removed,
 * but a deletion issued with NOTHING currently playing must not be delayed waiting for an engine
 * that was never going to show up.
 */
class SongDeletionAcknowledgmentTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    // SongDeletionAcknowledgment is a process-wide singleton, so its live-engine count leaks
    // between test methods unless each one detaches exactly what it attached.
    private var attachedInThisTest = 0

    private fun attachEngine() {
        SongDeletionAcknowledgment.engineAttached()
        attachedInThisTest++
    }

    @AfterTest fun detachWhatThisTestAttached() {
        repeat(attachedInThisTest) { SongDeletionAcknowledgment.engineDetached() }
    }

    @Test fun registerReturnsNullWithNoLiveEngine() {
        assertNull(
            SongDeletionAcknowledgment.register("song-x"),
            "a delete issued with nothing playing must have nothing to wait for",
        )
    }

    @Test fun registerReturnsAWaiterOnceAnEngineIsAttached() {
        attachEngine()
        val waiter = SongDeletionAcknowledgment.register("song-y")

        assertTrue(waiter != null, "a live engine must produce something to await")
        assertTrue(!waiter.isCompleted, "must not be pre-completed before signalCleared")
    }

    @Test fun signalClearedCompletesTheRegisteredWaiter() = runTest {
        attachEngine()
        val waiter = SongDeletionAcknowledgment.register("song-z")!!

        SongDeletionAcknowledgment.signalCleared("song-z")

        waiter.await() // must not hang — completed by signalCleared
    }

    @Test fun signalForAnUnregisteredSongIsHarmless() {
        // No register() call preceded this — must not throw.
        SongDeletionAcknowledgment.signalCleared("never-registered")
    }
}
