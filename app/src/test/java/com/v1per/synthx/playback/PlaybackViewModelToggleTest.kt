package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [PlaybackViewModel.toggleShuffle] and [PlaybackViewModel.cycleRepeat] compute their target from the
 * engine when they run, so back-to-back calls are two toggles, and shuffle OFF still goes through the
 * guarded path. The fake's repeat flow never moves, as an engine's flow can lag a set: only the
 * engine's current value gives the right second step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelToggleTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class FakeEngine(shuffle: Boolean, reordered: Boolean) : PlaybackEngine {
        val shuffleCalls = mutableListOf<Boolean>()
        val repeatCalls = mutableListOf<RepeatMode>()
        val shuffleState = MutableStateFlow(shuffle)
        val reorderedState = MutableStateFlow(reordered)
        var repeatNow = RepeatMode.Off
        override val isQueueEnded: Flow<Boolean> = MutableStateFlow(false)
        override val isPlaying: Flow<Boolean> = MutableStateFlow(true)
        override val isBuffering: Flow<Boolean> = emptyFlow()
        override val currentSongId: Flow<String?> = MutableStateFlow("a")
        override val currentSong: Flow<Song?> = MutableStateFlow(null)
        override val currentPosition: Flow<Long> = emptyFlow()
        override val duration: Flow<Long> = emptyFlow()
        override val errors: Flow<PlaybackError> = emptyFlow()
        override val queue: Flow<List<QueueSlot>> = MutableStateFlow(emptyList())
        override val currentIndex: Flow<Int> = MutableStateFlow(0)
        override val upcomingQueue: Flow<List<QueueSlot>> = MutableStateFlow(emptyList())
        override val shuffleEnabled: Flow<Boolean> = shuffleState
        override val shuffleReorderedByUser: Flow<Boolean> = reorderedState
        override val repeatMode: Flow<RepeatMode> = MutableStateFlow(RepeatMode.Off)
        override val hasNext: Flow<Boolean> = emptyFlow()
        override val hasPrevious: Flow<Boolean> = emptyFlow()
        override val isQueueCustomized: Flow<Boolean> = MutableStateFlow(false)
        override val queueGeneration: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {}
        override suspend fun restoreSavedQueue() {}
        override fun persistPlaybackState() {}
        override fun next() {}
        override fun previous() {}
        override suspend fun setShuffleEnabled(enabled: Boolean) {
            shuffleCalls += enabled
            shuffleState.value = enabled
            if (enabled) reorderedState.value = false
        }
        override fun setRepeatMode(mode: RepeatMode) {
            repeatCalls += mode
            repeatNow = mode
        }
        override fun currentRepeatMode(): RepeatMode = repeatNow
        override fun playNext(song: Song): Boolean = true
        override fun addToQueue(song: Song): Boolean = true
        override fun moveQueueItem(slotId: String, toIndex: Int) {}
        override fun removeQueueItem(slotId: String) {}
        override fun seekToQueueItem(slotId: String) {}
        override fun pause() {}
        override fun resume() {}
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
        override suspend fun reconcileWithDatabase() {}
    }

    private fun withViewModel(
        shuffle: Boolean = false,
        reordered: Boolean = false,
        block: suspend TestScope.(PlaybackViewModel, FakeEngine) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = FakeEngine(shuffle, reordered)
                val viewModel = PlaybackViewModel(PlaybackController(engine))
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun twoBackToBackShuffleTogglesSendOnThenOff() = withViewModel { vm, engine ->
        vm.toggleShuffle()
        vm.toggleShuffle()
        advanceUntilIdle()

        assertEquals(listOf(true, false), engine.shuffleCalls)
    }

    @Test fun twoBackToBackRepeatCyclesGoOffAllOne() = withViewModel { vm, engine ->
        vm.cycleRepeat()
        vm.cycleRepeat()
        advanceUntilIdle()

        assertEquals(listOf(RepeatMode.All, RepeatMode.One), engine.repeatCalls)
    }

    @Test fun aThirdRepeatCycleWrapsBackToOff() = withViewModel { vm, engine ->
        repeat(3) { vm.cycleRepeat() }
        advanceUntilIdle()

        assertEquals(listOf(RepeatMode.All, RepeatMode.One, RepeatMode.Off), engine.repeatCalls)
    }

    @Test fun shuffleOffAfterAReorderAsksOnceAndASecondTapDoesNotAskAgain() =
        withViewModel(shuffle = true, reordered = true) { vm, engine ->
            val shown = mutableListOf<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.shuffleOffConfirmation.collect { shown += it }
            }

            vm.toggleShuffle()
            advanceUntilIdle()
            vm.toggleShuffle()
            advanceUntilIdle()

            assertEquals(listOf(false, true), shown, "the question was raised exactly once")
            assertTrue(vm.shuffleOffConfirmation.value)
            assertEquals(emptyList(), engine.shuffleCalls, "nothing reaches the engine before an answer")

            vm.confirmShuffleOff()
            advanceUntilIdle()
            assertEquals(listOf(false), engine.shuffleCalls)
            assertFalse(vm.shuffleOffConfirmation.value)
        }

    @Test fun shuffleOnNeverAsks() = withViewModel(shuffle = false, reordered = true) { vm, engine ->
        vm.toggleShuffle()
        advanceUntilIdle()

        assertEquals(listOf(true), engine.shuffleCalls)
        assertFalse(vm.shuffleOffConfirmation.value)
    }

    @Test fun shuffleOffWithoutAReorderGoesStraightThrough() = withViewModel(shuffle = true) { vm, engine ->
        vm.toggleShuffle()
        advanceUntilIdle()

        assertEquals(listOf(false), engine.shuffleCalls)
        assertFalse(vm.shuffleOffConfirmation.value)
    }

    @Test fun theViewModelHasNoPublicStop() {
        val stops = listOf(PlaybackViewModel::class.java, PlaybackController::class.java, PlaybackEngine::class.java)
            .flatMap { type -> type.methods.filter { it.name == "stop" }.map { "${type.simpleName}.stop" } }
        assertEquals(emptyList(), stops)
    }
}
