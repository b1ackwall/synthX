package com.v1per.synthx.playback

import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A Play Next issued while a queue load still waits for the session connection waits for that load
 * on the real engine, then lands in the queue it installed. The first connection attempt is held
 * open through the engine's connection seam, which suspends the load at its controller wait.
 */
@RunWith(AndroidJUnit4::class)
class QueueLoadWaitInstrumentedTest : PlaybackServiceTestBase() {

    @Test fun aPlayNextWhileTheAlbumWaitsForTheSessionLandsInTheAlbum() {
        val attempts = AtomicInteger()
        val held = SettableFuture.create<MediaController>()
        val heldListener = AtomicReference<MediaController.Listener>()
        engine = newEngine { listener ->
            if (attempts.incrementAndGet() == 1) {
                heldListener.set(listener)
                held
            } else {
                realConnection(listener)
            }
        }
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        val album = songs(3, "load")

        val result = onMain {
            viewModel.playQueue(album, 0)
            viewModel.playNext(song("load-x"))
        }
        settle()

        assertEquals(QueueAddResult.Added, result)
        assertTrue("nothing is installed before the session connects", onMain { engine.orderStore.isEmpty })

        onMain { held.setFuture(realConnection(heldListener.get())) }

        await("the album with x after its first song") {
            onMain { engine.queue.now().songIds() } == listOf("load-0", "load-x", "load-1", "load-2")
        }
        assertEquals("load-0", onMain { engine.currentSongId.now() })
    }
}
