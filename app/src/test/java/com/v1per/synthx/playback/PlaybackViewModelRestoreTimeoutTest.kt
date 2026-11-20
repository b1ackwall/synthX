package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModelRestoreWaitTest.GatedRestoreEngine
import com.v1per.synthx.ui.testing.TestTimeouts
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
import kotlin.test.assertTrue

/**
 * The bound on the wait for a pending restore, on the coroutine scheduler's virtual clock: a
 * restore that never settles releases the waiting actions into today's fallback once the bound
 * passes, nothing waits after it, and a late restore completion never replays them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelRestoreTimeoutTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val timeoutMs = 2_000L

    private fun withViewModel(block: TestScope.(PlaybackViewModel, GatedRestoreEngine) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = GatedRestoreEngine()
                val viewModel = PlaybackViewModel(PlaybackController(engine), restoreWaitTimeoutMs = timeoutMs)
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val a = song("a")
    private val b = song("b")
    private val x = song("x")
    private val y = song("y")

    @Test fun theStateFollowsTheRestoreFromRequestToDone() = withViewModel { vm, engine ->
        assertEquals(QueueRestoreState.NotRequested, vm.queueRestoreState.value)

        vm.restoreSavedQueue()
        runCurrent()
        assertEquals(QueueRestoreState.Pending, vm.queueRestoreState.value)

        engine.release.complete(Unit)
        advanceUntilIdle()
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value)
    }

    @Test fun aRestoreThatNeverSettlesReleasesTheWaitingActionsAtTheBound() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.playNext(x)

        advanceTimeBy(timeoutMs - 1)
        runCurrent()
        assertEquals(emptyList(), engine.calls, "still inside the bound")
        assertEquals(QueueRestoreState.Pending, vm.queueRestoreState.value)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("playQueue:[x]"), engine.calls, "the bound passed: today's fallback")
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value)
    }

    @Test fun theBoundCoversEveryWaitingActionNotEachOne() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.addToQueue(y)
        advanceTimeBy(timeoutMs / 2)
        assertEquals(true, vm.keepQueueAndPlayNext(x))

        advanceTimeBy(timeoutMs / 2 + 1)
        runCurrent()

        assertTrue(engine.calls.isNotEmpty(), "the second action waited only for what was left of the bound")
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value)
    }

    @Test fun afterTheBoundNothingWaits() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        advanceTimeBy(timeoutMs + 1)
        runCurrent()

        vm.addToQueue(y)
        runCurrent()

        assertEquals(listOf("playQueue:[y]"), engine.calls)
    }

    @Test fun aLateRestoreCompletionRunsNothingAgain() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.playNext(x)
        advanceTimeBy(timeoutMs + 1)
        runCurrent()
        assertEquals(listOf("playQueue:[x]"), engine.calls)

        engine.restores = listOf(a, b) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[x]"), engine.calls, "the late restore replays no waiting action")
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value)
    }

    @Test fun aSecondRequestWhileTheFirstStillRunsStartsNoNewWait() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        advanceTimeBy(timeoutMs + 1)
        runCurrent()
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value)

        vm.restoreSavedQueue()
        runCurrent()
        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value, "the first restore is still running")

        vm.playNext(x)
        runCurrent()
        assertEquals(listOf("playQueue:[x]"), engine.calls)
    }

    @Test fun aRequestAfterTheFirstSettledSettlesWhenItsRestoreReturns() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        engine.release.complete(Unit)
        advanceUntilIdle()

        vm.restoreSavedQueue()
        runCurrent()

        assertEquals(QueueRestoreState.Done, vm.queueRestoreState.value, "the restore returned at once")
    }

    @Test fun theDefaultBoundOutlastsAFullFailedReconnectRound() {
        val backoff = generateSequence(0) { it + 1 }
            .map { ReconnectPolicy.nextDelayMsOrNull(it) }
            .takeWhile { it != null }
            .sumOf { it!! }
        assertEquals(7_000L, backoff, "the reconnect round this bound is sized against")
        assertTrue(
            QUEUE_RESTORE_WAIT_TIMEOUT_MS > backoff + ReconnectPolicy.MAX_ATTEMPTS * 1_000L,
            "the bound must outlast the backoff plus a second for each attempt",
        )
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
