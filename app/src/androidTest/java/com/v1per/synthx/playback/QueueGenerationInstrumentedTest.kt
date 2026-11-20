package com.v1per.synthx.playback

import android.net.Uri
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.SettableFuture
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue generation on the real engine and service. It goes up by one when a fresh play has
 * installed its queue and when a shuffle toggle was applied, from the app, from another controller
 * or after the confirmed shuffle-off question, and nowhere else: edits, Keep's insert, track changes,
 * an auto-skip and refused or pending toggles leave it as it is. Up Next is published before the
 * generation, so a screen that scrolls on the change never scrolls the list it replaces.
 */
@RunWith(AndroidJUnit4::class)
class QueueGenerationInstrumentedTest : PlaybackServiceTestBase() {

    private val recorder = RecordingQueueRepository()

    override fun createPersistence(): QueuePersistence = QueuePersistence(recorder)

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val createdFiles = mutableListOf<File>()

    @After fun cleanUp() {
        collectorScope.cancel()
        createdFiles.forEach { it.delete() }
    }

    private fun generation(target: ExoPlayerEngine = engine): Long = onMain { target.queueGeneration.value }

    /**
     * Records, in the order the main thread delivers them, every Up Next list (as its first slot id)
     * and every generation the engine publishes. The collectors are the same kind the view model and
     * the screen use: resumed on the main thread in emission order.
     */
    private fun recordPublishOrder(): List<String> {
        val events = CopyOnWriteArrayList<String>()
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.upcomingQueue.collect { events += "U:${it.firstOrNull()?.slotId}" }
        }
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.queueGeneration.collect { events += "G:$it" }
        }
        return events
    }

    /** The Up Next head published last before the generation reached [value]. */
    private fun upNextHeadBefore(events: List<String>, value: Long): String? {
        val at = events.indexOf("G:$value")
        assertTrue("generation $value was published: $events", at >= 0)
        return events.subList(0, at).lastOrNull { it.startsWith("U:") }?.removePrefix("U:")
    }

    private fun upNextHead(): String? = onMain { engine._upcomingQueue.value.firstOrNull()?.slotId }

    private fun upNextIds(): List<String> = onMain { engine._upcomingQueue.value.map { it.slotId } }

    private fun viewModel(): PlaybackViewModel {
        val viewModel = onMain { PlaybackViewModel(PlaybackController(engine)) }
        await("the view model sees the queue") {
            onMain { viewModel.queue.value.size == engine.queue.now().size }
        }
        settle()
        return viewModel
    }

    private fun corruptSong(id: String): Song {
        val file = File(context.cacheDir, "generation-corrupt-$id.wav")
        file.writeBytes(ByteArray(64 * 1024))
        createdFiles += file
        return song(id, title = "Bad $id").copy(filePath = Uri.fromFile(file).toString())
    }

    // --- bumped ---------------------------------------------------------------------------------

    @Test fun aFreshPlayBumpsOnceAfterItsUpNextIsPublished() {
        assertEquals(0L, generation())
        val events = recordPublishOrder()

        playQueueAndAwait(songs(5, "first"), 0)
        settle()
        assertEquals(1L, generation())
        assertEquals("Up Next lands before the generation", upNextHead(), upNextHeadBefore(events, 1L))

        playQueueAndAwait(songs(4, "second"), 1)
        settle()
        assertEquals(2L, generation())
        assertEquals(upNextHead(), upNextHeadBefore(events, 2L))
    }

    @Test fun aShuffledFreshPlayBumpsOnce() {
        runBlocking { engine.playQueue(songs(8, "shuffled"), 0, shuffle = true) }
        await("shuffled queue installed") { onMain { engine._shuffleEnabled.value && engine.orderStore.size == 8 } }
        settle()

        assertEquals(1L, generation())
    }

    @Test fun shuffleOnAndShuffleOffEachBumpOnceAfterUpNextIsPublished() {
        startQueue(12, 0, RepeatMode.Off)
        val start = generation()
        val events = recordPublishOrder()

        runBlocking { engine.setShuffleEnabled(true) }
        settle()
        assertEquals(start + 1, generation())
        assertEquals("the shuffled Up Next lands first", upNextHead(), upNextHeadBefore(events, start + 1))

        runBlocking { engine.setShuffleEnabled(false) }
        settle()
        assertEquals(start + 2, generation())
        assertEquals(upNextHead(), upNextHeadBefore(events, start + 2))
    }

    @Test fun anAppliedExternalShuffleBumpsOnAndOff() {
        startQueue(6, 0, RepeatMode.Off)
        val start = generation()

        onMain { extraController.shuffleModeEnabled = true }
        await("app shuffle on") { onMain { engine._shuffleEnabled.value } }
        settle()
        assertEquals(start + 1, generation())

        onMain { extraController.shuffleModeEnabled = false }
        await("app shuffle off") { onMain { !engine._shuffleEnabled.value } }
        settle()
        assertEquals(start + 2, generation())
    }

    @Test fun aConfirmedShuffleOffBumpsAndAPendingOrDismissedQuestionDoesNot() {
        startQueue(6, 0, RepeatMode.Off)
        runBlocking { engine.setShuffleEnabled(true) }
        settle()
        val reorderedRow = upNextIds()[3]
        onMain { engine.moveQueueItem(reorderedRow, 0) }
        settle()
        val vm = viewModel()
        assertTrue("precondition: reordered while shuffled", onMain { vm.shuffleReorderedByUser.value })
        val start = generation()

        onMain { vm.toggleShuffle() }
        settle()
        assertTrue("the question is open", onMain { vm.shuffleOffConfirmation.value })
        assertEquals("pending: no bump", start, generation())

        onMain { vm.dismissShuffleOffConfirmation() }
        settle()
        assertEquals("dismissed: no bump", start, generation())
        assertTrue(onMain { engine._shuffleEnabled.value })

        onMain { vm.toggleShuffle() }
        settle()
        onMain { vm.confirmShuffleOff() }
        await("shuffle off") { onMain { !engine._shuffleEnabled.value } }
        settle()
        assertEquals("confirmed: one bump", start + 1, generation())
    }

    @Test fun theViewModelsFreshPlayPathsBump() {
        startQueue(4, 0, RepeatMode.Off)
        val vm = viewModel()
        val start = generation()

        onMain { vm.requestPlayQueue(songs(3, "tap"), 1) }
        await("the tapped list plays") { onMain { engine.currentSongId.now() == "tap-1" } }
        settle()
        assertEquals("a song tap on an untouched queue", start + 1, generation())

        onMain { vm.playAll(songs(3, "page")) }
        await("the page plays") { onMain { engine.currentSongId.now() == "page-0" } }
        settle()
        assertEquals("a page Play", start + 2, generation())
    }

    // --- not bumped -----------------------------------------------------------------------------

    @Test fun editsTapsAndTrackChangesDoNotBump() {
        startQueue(8, 0, RepeatMode.Off)
        val start = generation()

        onMain { engine.playNext(song("edit-next")) }
        settle()
        onMain { engine.addToQueue(song("edit-add")) }
        settle()
        val movedRow = upNextIds()[4]
        onMain { engine.moveQueueItem(movedRow, 1) }
        settle()
        val removedRow = upNextIds()[2]
        onMain { engine.removeQueueItem(removedRow) }
        settle()
        onMain { engine.next() }
        settle()
        onMain { engine.previous() }
        settle()
        val tappedRow = upNextIds()[1]
        onMain { engine.seekToQueueItem(tappedRow) }
        settle()
        onMain { engine.setRepeatMode(RepeatMode.All) }
        settle()

        assertEquals(start, generation())
    }

    @Test fun aKeepThatInsertsDoesNotBump() {
        startQueue(5, 0, RepeatMode.Off)
        onMain { engine.playNext(song("keep-edit")) }
        settle()
        val vm = viewModel()
        val start = generation()

        val kept = song("keep-x")
        assertEquals(true, onMain { vm.keepQueueAndPlayNext(QueuePromptEvent(kept, listOf(kept), 0)) })
        await("the kept song plays") { onMain { engine.currentSongId.now() == "keep-x" } }
        settle()

        assertEquals(start, generation())
    }

    @Test fun aSongEndingOnItsOwnDoesNotBump() {
        playQueueAndAwait(songs(3, "end"), 0)
        val first = onMain { engine._currentSlotId.value }
        val start = generation()

        await("the first song ends", timeoutMs = 30_000L) {
            onMain {
                val onFirst = extraController.currentMediaItem?.mediaId == first
                if (onFirst && extraController.currentPosition < 58_000L) extraController.seekTo(59_300L)
                !onFirst
            }
        }
        settle()

        assertEquals(1, onMain { engine._currentIndex.value })
        assertEquals(start, generation())
    }

    @Test fun anAutoSkipDoesNotBump() {
        runBlocking { engine.playQueue(listOf(corruptSong("c"), song("ok-1"), song("ok-2")), 0) }
        await("skipped to the next song") { onMain { engine._currentIndex.value == 1 && extraController.isPlaying } }
        settle()

        assertEquals("only the fresh play counted", 1L, generation())
    }

    @Test fun aToggleThatChangesNothingOrHasNoCurrentSlotDoesNotBump() {
        await("connected") { onMain { engine.connection.isConnected } }
        runBlocking { engine.setShuffleEnabled(true) }
        settle()
        assertFalse("no current slot: refused", onMain { engine._shuffleEnabled.value })
        assertEquals(0L, generation())

        startQueue(4, 0, RepeatMode.Off)
        val start = generation()
        runBlocking { engine.setShuffleEnabled(false) }
        settle()
        assertEquals("already off: nothing applied", start, generation())
    }

    @Test fun anExternalShuffleOffRefusedOnAReorderedQueueDoesNotBump() {
        startQueue(5, 0, RepeatMode.Off)
        onMain { extraController.shuffleModeEnabled = true }
        await("app shuffle on") { onMain { engine._shuffleEnabled.value } }
        settle()
        val reorderedRow = upNextIds()[3]
        onMain { engine.moveQueueItem(reorderedRow, 0) }
        settle()
        val start = generation()

        onMain { extraController.shuffleModeEnabled = false }
        settle()

        assertTrue("refused: shuffle stays on", onMain { engine._shuffleEnabled.value })
        assertEquals(start, generation())
    }

    @Test fun aToggleOnAnEngineWithNoSessionDoesNotBump() {
        val held = SettableFuture.create<MediaController>()
        val unconnected = newEngine { held }

        runBlocking { unconnected.setShuffleEnabled(true) }

        assertFalse(onMain { unconnected._shuffleEnabled.value })
        assertEquals(0L, generation(unconnected))
    }
}
