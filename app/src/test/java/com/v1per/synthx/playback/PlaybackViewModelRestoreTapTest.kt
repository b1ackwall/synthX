package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModelRestoreWaitTest.GatedRestoreEngine
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Song taps and page Play/Shuffle while the saved-queue restore is still pending and nothing plays:
 * they wait for the restore alone, then decide from the engine's own customised flag and playback
 * state, so a restored hand-built queue raises its prompt instead of being replaced. Only the latest
 * held one runs, in arrival order with held Play Next and Add to Queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelRestoreTapTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val timeoutMs = 2_000L

    /** Shuffle's start index: whatever [next] holds when the request is built. */
    private class PinnedRandom : Random() {
        var next = 0
        override fun nextBits(bitCount: Int): Int = next
        override fun nextInt(until: Int): Int = next
    }

    private val random = PinnedRandom()

    private fun withViewModel(block: TestScope.(PlaybackViewModel, GatedRestoreEngine) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = GatedRestoreEngine().apply { installOnPlay = true }
                val viewModel = PlaybackViewModel(
                    PlaybackController(engine),
                    random = random,
                    restoreWaitTimeoutMs = timeoutMs,
                )
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val a = song("a")
    private val b = song("b")
    private val c = song("c")
    private val p = song("p")
    private val q = song("q")
    private val x = song("x")

    private fun TestScope.restoring(vm: PlaybackViewModel) {
        vm.restoreSavedQueue()
        runCurrent()
    }

    private fun GatedRestoreEngine.releaseWith(saved: List<Song>?, customised: Boolean) {
        restores = saved?.let { it to 0 }
        restoresCustomized = customised
        release.complete(Unit)
    }

    @Test fun aTapOverACustomisedSavedQueueRaisesTheKeepPromptAfterTheRestore() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p, q), 1)
        runCurrent()
        assertEquals(emptyList(), engine.calls, "nothing may be played before the restore finishes")
        assertNull(vm.queuePrompt.value, "nothing is shown while the tap is held")

        engine.releaseWith(listOf(a, b, c), customised = true)
        advanceUntilIdle()

        assertEquals(emptyList(), engine.calls, "the restored hand-built queue is kept")
        assertEquals(QueuePromptEvent(q, listOf(p, q), 1), vm.queuePrompt.value)
    }

    @Test fun aTapOverASavedQueueThatIsNotCustomisedPlaysAfterTheRestore() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p, q), 0)
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        engine.releaseWith(listOf(a, b), customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[p, q]"), engine.calls)
        assertNull(vm.queuePrompt.value)
    }

    @Test fun aTapWhenNothingWasSavedPlaysAfterTheRestore() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p), 0)
        engine.releaseWith(null, customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[p]"), engine.calls)
    }

    @Test fun aTapHeldPastTheBoundPlaysWithWhatIsKnown() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p, q), 0)

        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        assertEquals(emptyList(), engine.calls, "still inside the bound")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("playQueue:[p, q]"), engine.calls, "the bound passed: decided on what is playing")
    }

    @Test fun twoTapsDuringTheRestoreOnlyPlayTheLast() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p), 0)
        vm.requestPlayQueue(listOf(q), 0)

        engine.releaseWith(listOf(a, b), customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[q]"), engine.calls)
    }

    @Test fun aTapThenAPlayNextApplyInThatOrder() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p, q), 0)
        assertEquals(QueueAddResult.Added, vm.playNext(x))

        engine.releaseWith(listOf(a, b), customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[p, q]", "playNext:x"), engine.calls, "x goes after p in the tapped queue")
    }

    @Test fun aPlayNextThenATapApplyInThatOrder() = withViewModel { vm, engine ->
        restoring(vm)
        assertEquals(QueueAddResult.Added, vm.playNext(x))
        vm.requestPlayQueue(listOf(p, q), 0)

        engine.releaseWith(listOf(a, b), customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playNext:x"), engine.calls, "x goes into the restored queue first")
        assertNotNull(vm.queuePrompt.value, "the tap then finds the queue Play Next customised")
    }

    @Test fun aHeldTapDoesNotWaitForAQueueLoadSoItStillCancelsIt() = withViewModel { vm, engine ->
        engine.loadGate = CompletableDeferred()
        restoring(vm)
        assertEquals(QueueAddResult.Added, vm.playNext(x))
        vm.requestPlayQueue(listOf(p, q), 0)

        engine.releaseWith(null, customised = false)
        runCurrent()

        assertEquals(
            listOf("playQueue:[x]", "cancelled:[x]", "playQueue:[p, q]"),
            engine.calls,
            "Play Next's fallback load is still running when the tap plays, and the newer play wins",
        )
    }

    @Test fun aTapDecidedAtOnceDropsAnEarlierHeldTap() = withViewModel { vm, engine ->
        engine.loadGate = CompletableDeferred()
        restoring(vm)
        // Play Next's fallback holds the line on its load, so the held tap behind it is still waiting.
        vm.playNext(x)
        vm.playQueue(listOf(c), 0)
        vm.requestPlayQueue(listOf(p), 0)
        engine.releaseWith(null, customised = false)
        runCurrent()
        assertEquals(listOf("playQueue:[c]"), engine.calls, "Play Next waits for the load of c")

        vm.requestPlayQueue(listOf(q), 0)
        engine.loadGate = null
        runCurrent()

        assertEquals(
            listOf("playQueue:[c]", "cancelled:[c]", "playQueue:[q]", "playNext:x"),
            engine.calls,
            "the held tap of p is dropped by the newer tap of q, and Play Next then lands in q's queue",
        )
        assertNull(vm.queuePrompt.value, "the dropped tap of p never asks about q's queue either")
    }

    @Test fun playOverACustomisedSavedQueueRaisesTheReplacePromptAfterTheRestore() = withViewModel { vm, engine ->
        restoring(vm)
        vm.playAll(listOf(p, q))
        runCurrent()
        assertNull(vm.queueReplacePrompt.value)

        engine.releaseWith(listOf(a, b), customised = true)
        advanceUntilIdle()

        assertEquals(emptyList(), engine.calls)
        assertEquals(PlayAllRequest(listOf(p, q), 0, false), vm.queueReplacePrompt.value?.request)
    }

    @Test fun playOverASavedQueueThatIsNotCustomisedPlaysAfterTheRestore() = withViewModel { vm, engine ->
        restoring(vm)
        vm.playAll(listOf(p, q))
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        engine.releaseWith(listOf(a, b), customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[p, q]"), engine.calls)
    }

    @Test fun playHeldPastTheBoundPlays() = withViewModel { vm, engine ->
        restoring(vm)
        vm.playAll(listOf(p, q))
        advanceTimeBy(timeoutMs + 1)
        runCurrent()

        assertEquals(listOf("playQueue:[p, q]"), engine.calls)
    }

    @Test fun shuffleKeepsTheStartChosenAtTapTime() = withViewModel { vm, engine ->
        restoring(vm)
        random.next = 2
        vm.shuffleAll(listOf(a, b, c))
        random.next = 0

        engine.releaseWith(null, customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[a, b, c]"), engine.calls)
        assertEquals(2, engine.plays.single().first)
    }

    @Test fun aTapThenPlayDuringTheRestoreOnlyPlaysThePlay() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p), 0)
        vm.playAll(listOf(q, c))
        vm.shuffleAll(listOf(a, b))

        engine.releaseWith(null, customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[a, b]"), engine.calls)
    }

    @Test fun aTapAfterTheRestoreInstalledKeepsTheSavedModes() = withViewModel { vm, engine ->
        restoring(vm)
        vm.requestPlayQueue(listOf(p, q), 0)
        engine.restoresModes = true to RepeatMode.All

        engine.releaseWith(listOf(a, b), customised = false)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[p, q]"), engine.calls)
        assertEquals(
            Triple(0, true, RepeatMode.All),
            engine.plays.single(),
            "the tap plays after the restore set the saved shuffle and repeat",
        )
    }

    @Test fun aTapAgainstActivePlaybackNeverWaits() = withViewModel { vm, engine ->
        engine.install(listOf(a, b), 0)
        advanceUntilIdle()
        restoring(vm)

        vm.requestPlayQueue(listOf(p), 0)
        runCurrent()

        assertEquals(listOf("playQueue:[p]"), engine.calls)
    }

    @Test fun withNoRestoreRequestedATapPlaysAtOnce() = withViewModel { vm, engine ->
        vm.requestPlayQueue(listOf(p), 0)
        runCurrent()

        assertEquals(listOf("playQueue:[p]"), engine.calls)
    }

    private fun song(id: String) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        duration = 60_000L,
        filePath = "file:///$id.mp3",
    )
}
