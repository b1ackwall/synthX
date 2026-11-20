package com.v1per.synthx.playback

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The view model hands the engine's queue generation to the screen as it is: no copy that starts
 * at a default and catches up later. Up Next scrolls to the top when the value changes, so a copy
 * that starts at 0 would read as a change on the first collection and scroll a list nobody touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelQueueGenerationTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    // Never advanced: whatever the view model needs a coroutine for has not run.
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theGenerationIsTheEnginesOwnFlowWithItsCurrentValue() {
        val engine = QueueModelEngine()
        engine.generation.value = 7L

        val viewModel = PlaybackViewModel(PlaybackController(engine))

        assertSame(engine.queueGeneration, viewModel.queueGeneration)
        assertEquals(7L, viewModel.queueGeneration.value)
    }

    @Test fun theGenerationFollowsTheEngineWithoutACollector() {
        val engine = QueueModelEngine()
        val viewModel = PlaybackViewModel(PlaybackController(engine))

        engine.generation.value = 3L

        assertEquals(3L, viewModel.queueGeneration.value)
    }
}
