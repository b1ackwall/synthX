package com.v1per.synthx.playback

import android.net.Uri
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A song whose file is missing makes the player fail. With a next slot, playback moves on to it with
 * one error message; with none, the failing song stays shown, and both Next and Previous then bring
 * the player back to life on the target song rather than seeking silently on a dead player.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRecoveryInstrumentedTest : PlaybackServiceTestBase() {

    private val errors = CopyOnWriteArrayList<PlaybackError>()
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stopCollecting() = collectorScope.cancel()

    private fun missingSong(id: String): Song = song(
        id,
        title = "Missing $id",
    ).copy(filePath = Uri.fromFile(File(context.cacheDir, "slice1-$id.wav")).toString())

    private fun playAndAwaitFailure(list: List<Song>, startIndex: Int) {
        // Undispatched, so the collector is subscribed before anything can fail (errors keep no replay).
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.errors.collect { errors += it } }
        playQueueAndAwait(list, startIndex)
        await("player failed") { onMain { extraController.playerError != null } }
    }

    private fun awaitRecoveredOn(slotId: String) = await("recovered on $slotId") {
        onMain {
            extraController.currentMediaItem?.mediaId == slotId &&
                extraController.playbackState != Player.STATE_IDLE
        }
    }

    @Test fun aMissingFileSkipsToTheNextSongAndSendsOneErrorMessage() {
        // Undispatched, so the collector is subscribed before anything can fail (errors keep no replay).
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.errors.collect { errors += it } }
        runBlocking { engine.playQueue(listOf(missingSong("err-shown"), song("err-after")), 0) }
        val next = onMain { engine.queue.now()[1].slotId }
        await("the next song plays") {
            onMain { extraController.currentMediaItem?.mediaId == next && extraController.isPlaying }
        }
        settle()

        assertEquals(listOf<PlaybackError>(PlaybackError.PlaybackFailed("Missing err-shown")), errors.toList())
        assertEquals("err-after", onMain { engine.currentSongId.now() })
        assertEquals(true, onMain { engine.isPlaying.now() })
    }

    @Test fun nextAfterAPlaybackErrorRecoversThePlayer() {
        // Five bad songs in a row: the burst stops on the fifth at the cap, with "ok" still next.
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.errors.collect { errors += it } }
        runBlocking { engine.playQueue((0 until 5).map { missingSong("err-next-$it") } + song("ok-next"), 0) }
        val stoppedOn = onMain { engine.queue.now()[4].slotId }
        await("player failed on the fifth bad song") {
            onMain { extraController.currentMediaItem?.mediaId == stoppedOn && extraController.playerError != null }
        }
        val next = onMain { engine.queue.now()[5].slotId }

        onMain { engine.next() }

        awaitRecoveredOn(next)
    }

    @Test fun previousAfterAPlaybackErrorRecoversThePlayer() {
        playAndAwaitFailure(listOf(song("ok-prev"), missingSong("err-prev")), 1)
        val previous = onMain { engine.queue.now()[0].slotId }

        onMain { engine.previous() }

        awaitRecoveredOn(previous)
    }
}
