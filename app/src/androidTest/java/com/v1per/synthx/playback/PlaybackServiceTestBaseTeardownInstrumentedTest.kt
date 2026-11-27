package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.DatabaseStartupGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The base's teardown, which keeps a late service destroy away from the real database: the service's
 * exit checkpoint writes into the class recorder whenever the service is destroyed during the class,
 * the startup gate stays ready from one test to the next, and the gate is handed back only once the
 * service is destroyed. Tests run in name order: the readiness check needs a test before it.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PlaybackServiceTestBaseTeardownInstrumentedTest : PlaybackServiceTestBase() {

    private var gateReadyAtStart = false

    @Before fun recordTheGateAtStart() {
        gateReadyAtStart = DatabaseStartupGate.isReady()
    }

    /** Leaves a loaded queue behind, so a destroy after this test would checkpoint it. */
    @Test fun a_aTestLeavesItemsLoadedInTheService() {
        playQueueAndAwait(songs(3, "left"), 1)
        assertTrue(gateReadyAtStart)
    }

    @Test fun b_theGateIsStillReadyInTheNextTest() {
        assertTrue("the gate is never reset between tests", gateReadyAtStart)
        assertTrue(DatabaseStartupGate.isReady())
    }

    @Test fun c_aDestroyDuringTheClassCheckpointsIntoTheClassRecorder() {
        assertSame(classExitPersistence, SynthXPlaybackService.exitPersistenceOverride)
        playQueueAndAwait(songs(3, "late"), 2)
        val slot = onMain { engine._currentSlotId.value }
        val before = classExitRecorder.writesOf<QueueWrite.Cursor>().size

        releaseEngine()
        releaseExtraController()
        PlaybackServiceLifecycle.stopUntilDestroyed(context)

        await("the destroy's checkpoint recorded") { classExitRecorder.writesOf<QueueWrite.Cursor>().size > before }
        assertEquals(slot, classExitRecorder.writesOf<QueueWrite.Cursor>().last().currentSlotId)
    }

    @Test fun d_theGateIsNotResetWhileTheServiceIsAlive() {
        assertTrue("precondition: a controller holds the service", PlaybackServiceLifecycle.isRunning(context))
        try {
            resetGateOnceServiceIsDestroyed(context)
            fail("the gate was reset under a live service")
        } catch (expected: IllegalStateException) {
            assertTrue(DatabaseStartupGate.isReady())
        }
    }

    /** The class-end step itself, run here and then re-armed for the real class end. */
    @Test fun e_classEndDestroysTheServiceBeforeResettingTheGate() {
        playQueueAndAwait(songs(3, "end"), 0)
        val slot = onMain { engine._currentSlotId.value }
        val recorder = classExitRecorder
        releaseEngine()
        releaseExtraController()

        destroyServiceThenResetGate()

        assertFalse(PlaybackServiceLifecycle.isRunning(context) || PlaybackServiceLifecycle.isDestroying(context))
        assertFalse("the gate is reset", DatabaseStartupGate.isReady())
        assertEquals(null, SynthXPlaybackService.exitPersistenceOverride)
        assertEquals(
            "the destroy checkpointed before the gate went",
            slot,
            recorder.writesOf<QueueWrite.Cursor>().last().currentSlotId,
        )
        installClassExitCheckpoint()
    }
}
