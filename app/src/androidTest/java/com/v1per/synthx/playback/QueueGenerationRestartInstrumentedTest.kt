package com.v1per.synthx.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue generation is not bumped by the two ways an engine comes back to a queue it did not
 * install itself: the cold-start restore of the saved queue, and the adoption of a session that is
 * still running. Both bring back the user's own queue, so Up Next keeps its place.
 */
@RunWith(AndroidJUnit4::class)
internal class QueueGenerationRestartInstrumentedTest : QueueRestartTestBase() {

    @Test fun theColdStartRestoreDoesNotBump() {
        play(songs(5, "restore"), 2)

        val restored = restartFrom(writes())

        assertEquals("the saved queue is back", 5, onMain { restored.orderStore.size })
        assertEquals(0L, onMain { restored.queueGeneration.value })
    }

    @Test fun adoptingARunningSessionDoesNotBump() {
        play(songs(5, "adopt"), 1)
        // The adopting engine reads the saved queue the first engine wrote; the session keeps playing.
        recorder.adoptionRead = QueueDiskImage.after(writes()).read(library)
        releaseEngine(engine)

        val adopter = newEngine()
        await("the adopting engine connected") { onMain { adopter.connection.isConnected } }
        await("the session adopted") { onMain { adopter.orderStore.size == 5 } }
        settle()

        assertTrue("adopted the playing slot", onMain { adopter._currentSlotId.value != null })
        assertEquals(0L, onMain { adopter.queueGeneration.value })
    }
}
