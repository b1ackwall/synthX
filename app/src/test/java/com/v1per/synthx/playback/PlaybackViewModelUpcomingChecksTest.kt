package com.v1per.synthx.playback

import com.v1per.synthx.playback.QueueModelEngine.Companion.song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The duplicate checks of Play Next and Add to Queue read the live Up Next list whether or not any
 * UI collects the public, screen-scoped [PlaybackViewModel.upcomingQueue].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelUpcomingChecksTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun withViewModel(block: TestScope.(PlaybackViewModel, QueueModelEngine) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = QueueModelEngine()
                val viewModel = PlaybackViewModel(PlaybackController(engine))
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val c = song("c")
    private val a = song("a")
    private val b = song("b")
    private val x = song("x")

    @Test fun aPlayNextOfTheSongAlreadyNextAsksWithNobodyCollectingUpNext() = withViewModel { vm, engine ->
        engine.install(listOf(c, x, a), 0)
        advanceUntilIdle()

        assertEquals(QueueAddResult.NeedsConfirmation, vm.playNext(x))
        assertEquals(emptyList(), engine.calls)
    }

    @Test fun anAddToQueueOfTheSongAlreadyLastAsksWithNobodyCollectingUpNext() = withViewModel { vm, engine ->
        engine.install(listOf(c, a, x), 0)
        advanceUntilIdle()

        assertEquals(QueueAddResult.NeedsConfirmation, vm.addToQueue(x))
        assertEquals(emptyList(), engine.calls)
    }

    @Test fun theChecksFollowUpNextAfterItsLastCollectorLeft() = withViewModel { vm, engine ->
        engine.install(listOf(c, x, a), 0)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.upcomingQueue.collect {} }
        advanceUntilIdle()
        collector.cancel()
        // Past the public flow's stop timeout, so it no longer follows the engine.
        advanceTimeBy(6_000)
        engine.install(listOf(c, a, b), 0)
        advanceUntilIdle()

        assertEquals(QueueAddResult.Added, vm.playNext(x), "x is no longer next")
        assertEquals(listOf("c", "x", "a", "b"), engine.songIds())
    }
}
