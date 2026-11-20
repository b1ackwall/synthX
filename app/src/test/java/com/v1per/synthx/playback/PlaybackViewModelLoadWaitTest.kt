package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.QueueModelEngine.Companion.song
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Play Next and Add to Queue issued while a queue load is still starting (suspended on its artwork
 * or its session connection) wait for that load, then apply to the queue it installed. The engine's
 * loads are held on gates here, so every interleaving is decided by the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelLoadWaitTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    // The production bound for the wait, on the test scheduler's virtual clock.
    private val boundMs = 15_000L

    private fun withViewModel(
        restoreWaitTimeoutMs: Long = QUEUE_RESTORE_WAIT_TIMEOUT_MS,
        block: TestScope.(PlaybackViewModel, QueueModelEngine) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = QueueModelEngine()
                val viewModel =
                    PlaybackViewModel(PlaybackController(engine), restoreWaitTimeoutMs = restoreWaitTimeoutMs)
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val o0 = song("o0")
    private val o1 = song("o1")
    private val a0 = song("a0")
    private val a1 = song("a1")
    private val a2 = song("a2")
    private val b0 = song("b0")
    private val b1 = song("b1")
    private val x = song("x")
    private val y = song("y")
    private val album = listOf(a0, a1, a2)

    private fun TestScope.playingOldQueue(engine: QueueModelEngine) {
        engine.install(listOf(o0, o1), 0)
        advanceUntilIdle()
    }

    private fun TestScope.startHeldLoad(vm: PlaybackViewModel, engine: QueueModelEngine, songs: List<Song> = album) =
        CompletableDeferred<Unit>().also { gate ->
            engine.loadGates.addLast(gate)
            vm.playQueue(songs, 0)
            runCurrent()
        }

    @Test fun aPlayNextDuringALoadLandsInTheNewQueueNotTheOldOne() = withViewModel { vm, engine ->
        playingOldQueue(engine)
        val gate = startHeldLoad(vm, engine)

        assertEquals(QueueAddResult.Added, vm.playNext(x))
        runCurrent()
        assertFalse("playNext:x" in engine.calls, "the insert must wait for the load")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a0", "x", "a1", "a2"), engine.songIds())
    }

    @Test fun anAddToQueueDuringALoadAppendsToTheNewQueue() = withViewModel { vm, engine ->
        playingOldQueue(engine)
        val gate = startHeldLoad(vm, engine)

        assertEquals(QueueAddResult.Added, vm.addToQueue(x))
        runCurrent()
        assertFalse("addToQueue:x" in engine.calls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a0", "a1", "a2", "x"), engine.songIds())
    }

    @Test fun aPlayNextWithNothingPlayingDoesNotCancelTheLoadingAlbum() = withViewModel { vm, engine ->
        val gate = startHeldLoad(vm, engine)

        assertEquals(QueueAddResult.Added, vm.playNext(x))
        runCurrent()
        assertEquals(listOf("playQueue:[a0, a1, a2]@0"), engine.calls, "no one-song queue while the album loads")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("a0", "x", "a1", "a2"), engine.songIds())
        assertEquals(0, engine.currentIndex())
    }

    @Test fun aLoadThatInstallsNothingRunsTheOneSongFallbackAfterIt() = withViewModel { vm, engine ->
        engine.loadsInstall = false
        val gate = startHeldLoad(vm, engine)

        vm.addToQueue(x)
        runCurrent()
        assertEquals(listOf("playQueue:[a0, a1, a2]@0"), engine.calls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[a0, a1, a2]@0", "playQueue:[x]@0"), engine.calls)
    }

    @Test fun aNewerLoadSupersedesTheOlderAndTheWaitingActionFollowsIt() = withViewModel { vm, engine ->
        startHeldLoad(vm, engine)
        vm.playNext(x)
        val second = startHeldLoad(vm, engine, listOf(b0, b1))
        runCurrent()
        assertFalse("playNext:x" in engine.calls)

        second.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("b0", "x", "b1"), engine.songIds())
        assertFalse(engine.calls.any { it.startsWith("installed:[a0") }, "the superseded album never installs")
        assertFalse(engine.calls.contains("playQueue:[x]@0"))
    }

    @Test fun aLoadCancelledByANewerOneLeavesNothingWaiting() = withViewModel { vm, engine ->
        startHeldLoad(vm, engine)
        vm.playQueue(listOf(b0, b1), 0)
        advanceUntilIdle()
        assertEquals(listOf("b0", "b1"), engine.songIds())

        vm.playNext(x)

        assertTrue("playNext:x" in engine.calls, "a finished load must not hold the next action")
    }

    @Test fun aLoadThatNeverFinishesReleasesTheActionAtTheBoundOnlyOnce() = withViewModel { vm, engine ->
        playingOldQueue(engine)
        val gate = startHeldLoad(vm, engine)
        vm.playNext(x)

        advanceTimeBy(boundMs - 1)
        runCurrent()
        assertFalse("playNext:x" in engine.calls, "still inside the bound")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, engine.calls.count { it == "playNext:x" }, "at the bound it acts on what is playing")

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, engine.calls.count { it == "playNext:x" }, "the late load must not run the action again")
    }

    @Test fun anActionWithNothingPendingActsAtOnce() = withViewModel { vm, engine ->
        playingOldQueue(engine)

        assertEquals(QueueAddResult.Added, vm.playNext(x))

        assertEquals(listOf("o0", "x", "o1"), engine.songIds())
    }

    @Test fun aRestoreAndALoadPendingTogetherShareOneBound() = withViewModel(restoreWaitTimeoutMs = 60_000L) {
            vm,
            engine,
        ->
        engine.restoreGate = CompletableDeferred()
        vm.restoreSavedQueue()
        runCurrent()
        startHeldLoad(vm, engine)
        vm.playNext(x)

        advanceTimeBy(boundMs - 1)
        runCurrent()
        assertFalse(engine.calls.contains("playQueue:[x]@0"))

        advanceTimeBy(2)
        runCurrent()
        assertTrue(engine.calls.contains("playQueue:[x]@0"), "one bound covers both waits")
    }

    @Test fun anActionWaitsForTheRestoreAfterTheLoadFinishes() = withViewModel { vm, engine ->
        val restore = CompletableDeferred<Unit>()
        engine.restoreGate = restore
        vm.restoreSavedQueue()
        runCurrent()
        val load = startHeldLoad(vm, engine)
        vm.playNext(x)

        load.complete(Unit)
        runCurrent()
        assertFalse("playNext:x" in engine.calls, "the restore is still pending")

        restore.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a0", "x", "a1", "a2"), engine.songIds())
    }

    @Test fun aFallbackLoadDoesNotWaitForItselfAndTheNextHeldActionWaitsForIt() = withViewModel { vm, engine ->
        val restore = CompletableDeferred<Unit>()
        engine.restoreGate = restore
        vm.restoreSavedQueue()
        runCurrent()
        vm.playNext(x)
        vm.playNext(y)

        restore.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("x", "y"), engine.songIds(), "y lands after the one-song queue x started")
        assertEquals(1, engine.calls.count { it.startsWith("playQueue:") })
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value)
    }
}
