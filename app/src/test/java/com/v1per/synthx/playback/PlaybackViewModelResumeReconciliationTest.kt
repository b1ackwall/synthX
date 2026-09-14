package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P4-T5's bounded optimistic-play-state reconciliation: [PlaybackViewModel.resume] sets
 * `isPlaying` optimistically before the engine confirms anything, and a legitimate failure
 * (audio focus denied, a player left dead by a prior fatal error) can mean the engine's real
 * `isPlaying` flow never emits a correcting value at all — see [PlaybackViewModel.resume]'s
 * kdoc. This is fully coverable at the pure-JVM level: [FakeEngine] needs no `MediaController`,
 * only the plain [PlaybackEngine] interface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelResumeReconciliationTest {

    /** Minimal [PlaybackEngine] fake — only [isPlaying] is exercised by these tests. */
    private class FakeEngine : PlaybackEngine {
        val _isPlaying = MutableStateFlow(false)
        override val isPlaying: Flow<Boolean> = _isPlaying.asStateFlow()
        override val isBuffering: Flow<Boolean> = emptyFlow()
        override val currentSongId: Flow<String?> = emptyFlow()
        override val currentSong: Flow<Song?> = emptyFlow()
        override val currentPosition: Flow<Long> = emptyFlow()
        override val duration: Flow<Long> = emptyFlow()
        override val errors: Flow<PlaybackError> = emptyFlow()
        override val queue: Flow<List<QueueSlot>> = emptyFlow()
        override val currentIndex: Flow<Int> = emptyFlow()
        override val upcomingQueue: Flow<List<QueueSlot>> = emptyFlow()
        override val shuffleEnabled: Flow<Boolean> = emptyFlow()
        override val repeatMode: Flow<RepeatMode> = emptyFlow()
        override val hasNext: Flow<Boolean> = emptyFlow()
        override val hasPrevious: Flow<Boolean> = emptyFlow()
        override val isQueueCustomized: Flow<Boolean> = emptyFlow()
        override suspend fun playQueue(songs: List<Song>, startIndex: Int) {}
        override suspend fun restoreSavedQueue() {}
        override fun persistPlaybackState() {}
        override fun next() {}
        override fun previous() {}
        override suspend fun setShuffleEnabled(enabled: Boolean) {}
        override fun setRepeatMode(mode: RepeatMode) {}
        override fun playNext(song: Song) = true
        override fun addToQueue(song: Song) = true
        override fun moveQueueItem(slotId: String, toIndex: Int) {}
        override fun removeQueueItem(slotId: String) {}
        override fun seekToQueueItem(slotId: String) {}
        override fun pause() {}
        // Deliberately does NOT flip _isPlaying — simulates a resume() that never actually
        // starts playback (focus denied, or a player still dead from a prior fatal error).
        override fun resume() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
        override suspend fun reconcileWithDatabase() {}
    }

    @Test fun aResumeThatNeverActuallyStartsIsForceCorrectedAfterTheTimeout() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine()
            val viewModel = PlaybackViewModel(PlaybackController(engine))
            // Drains the init-block collector's initial replay of the engine's current (false)
            // state — in production this happens well before any tap, since viewModelScope
            // uses Dispatchers.Main.immediate; StandardTestDispatcher needs an explicit drain
            // to reach the same starting point instead of racing it against resume() below.
            advanceUntilIdle()

            viewModel.resume()
            assertTrue(viewModel.isPlaying.value, "optimistic true immediately after resume()")

            advanceTimeBy(1_499)
            assertTrue(viewModel.isPlaying.value, "must not correct before the timeout elapses")

            advanceTimeBy(2)
            assertFalse(viewModel.isPlaying.value, "engine never confirmed playback; must self-correct")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun aLegitimateLateArrivingTrueIsNeverFought() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine()
            val viewModel = PlaybackViewModel(PlaybackController(engine))
            advanceUntilIdle()

            viewModel.resume()
            advanceTimeBy(500)
            // The engine genuinely starts playing shortly before the timeout fires.
            engine._isPlaying.value = true
            advanceTimeBy(2_000)

            assertTrue(viewModel.isPlaying.value, "a genuine late confirmation must win")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun aPauseBeforeTheTimeoutIsNotOverriddenByTheStaleReconciliation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine()
            val viewModel = PlaybackViewModel(PlaybackController(engine))
            advanceUntilIdle()

            viewModel.resume()
            advanceTimeBy(200)
            viewModel.pause()
            advanceTimeBy(2_000)

            assertFalse(viewModel.isPlaying.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun aSecondResumeSupersedesTheFirstTimeoutsCheck() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine()
            val viewModel = PlaybackViewModel(PlaybackController(engine))
            advanceUntilIdle()

            viewModel.resume()
            advanceTimeBy(1_400)
            // A second tap restarts the window; the engine confirms shortly after it.
            viewModel.resume()
            engine._isPlaying.value = true
            advanceTimeBy(1_500)

            assertTrue(viewModel.isPlaying.value, "second resume's own confirmation must stick")
            advanceTimeBy(2_000)
            assertTrue(viewModel.isPlaying.value, "the superseded first timeout must not fire late")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
