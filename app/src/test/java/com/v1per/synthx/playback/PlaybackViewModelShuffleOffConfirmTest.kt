package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * The guarded shuffle-off path: while the user has reordered a shuffled queue, turning shuffle off
 * asks first, and only Confirm reaches the engine. The pending question lives in the ViewModel, so
 * nothing about it depends on a screen collecting a flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelShuffleOffConfirmTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private class FakeEngine(reordered: Boolean) : PlaybackEngine {
        val shuffleCalls = mutableListOf<Boolean>()
        val reorderedState = MutableStateFlow(reordered)
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
        override val shuffleEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val shuffleReorderedByUser: Flow<Boolean> = reorderedState
        override val repeatMode: Flow<RepeatMode> = emptyFlow()
        override val hasNext: Flow<Boolean> = emptyFlow()
        override val hasPrevious: Flow<Boolean> = emptyFlow()
        override val isQueueCustomized: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {}
        override suspend fun restoreSavedQueue() {}
        override fun persistPlaybackState() {}
        override fun next() {}
        override fun previous() {}
        override suspend fun setShuffleEnabled(enabled: Boolean) {
            shuffleCalls += enabled
        }
        override fun setRepeatMode(mode: RepeatMode) {}
        override fun playNext(song: Song): Boolean = true
        override fun addToQueue(song: Song): Boolean = true
        override fun moveQueueItem(slotId: String, toIndex: Int) {}
        override fun removeQueueItem(slotId: String) {}
        override fun seekToQueueItem(slotId: String) {}
        override fun pause() {}
        override fun resume() {}
        override fun currentRepeatMode(): RepeatMode = RepeatMode.Off
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
        override suspend fun reconcileWithDatabase() {}
    }

    private fun withViewModel(reordered: Boolean, block: TestScope.(PlaybackViewModel, FakeEngine) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = FakeEngine(reordered)
                val viewModel = PlaybackViewModel(PlaybackController(engine))
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun shuffleOffAfterAReorderAsksInsteadOfToggling() = withViewModel(reordered = true) { vm, engine ->
        vm.setShuffleEnabled(false)
        advanceUntilIdle()

        assertTrue(vm.shuffleOffConfirmation.value)
        assertEquals(emptyList(), engine.shuffleCalls)
    }

    @Test fun confirmTurnsShuffleOffOnceAndClosesTheQuestion() = withViewModel(reordered = true) { vm, engine ->
        vm.setShuffleEnabled(false)
        advanceUntilIdle()

        vm.confirmShuffleOff()
        advanceUntilIdle()

        assertEquals(listOf(false), engine.shuffleCalls)
        assertFalse(vm.shuffleOffConfirmation.value)
    }

    @Test fun dismissKeepsShuffleOnAndKeepsTheFlag() = withViewModel(reordered = true) { vm, engine ->
        vm.setShuffleEnabled(false)
        advanceUntilIdle()

        vm.dismissShuffleOffConfirmation()
        advanceUntilIdle()

        assertEquals(emptyList(), engine.shuffleCalls)
        assertFalse(vm.shuffleOffConfirmation.value)
        assertTrue(vm.shuffleReorderedByUser.value)
    }

    @Test fun withoutAReorderShuffleOffGoesStraightThrough() = withViewModel(reordered = false) { vm, engine ->
        vm.setShuffleEnabled(false)
        advanceUntilIdle()

        assertEquals(listOf(false), engine.shuffleCalls)
        assertFalse(vm.shuffleOffConfirmation.value)
    }

    @Test fun shuffleOnNeverAsks() = withViewModel(reordered = true) { vm, engine ->
        vm.setShuffleEnabled(true)
        advanceUntilIdle()

        assertEquals(listOf(true), engine.shuffleCalls)
        assertFalse(vm.shuffleOffConfirmation.value)
    }

    @Test fun theFlagIsReadWithoutAnyScreenCollectingIt() = withViewModel(reordered = false) { vm, engine ->
        engine.reorderedState.value = true
        advanceUntilIdle()

        vm.setShuffleEnabled(false)
        advanceUntilIdle()

        assertTrue(vm.shuffleOffConfirmation.value)
        assertEquals(emptyList(), engine.shuffleCalls)
    }
}
