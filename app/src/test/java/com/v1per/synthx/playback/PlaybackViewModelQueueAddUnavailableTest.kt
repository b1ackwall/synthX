package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #175: while the engine is between session connections it refuses queue inserts (returns
 * false and reports [PlaybackError.ServiceUnavailable] itself). The ViewModel must pass that on
 * rather than telling the UI the song was added — which is what it did while
 * [PlaybackEngine.playNext]/[PlaybackEngine.addToQueue] were documented as always-true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelQueueAddUnavailableTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private val current = song("current")
    private val tapped = song("tapped")

    /** Active playback (so inserts reach the engine) and a configurable insert answer. */
    private class FakeEngine(
        current: Song,
        var acceptsInserts: Boolean,
        queueEnded: Boolean = false,
    ) : PlaybackEngine {
        var playNextCalls = 0
        var addToQueueCalls = 0
        var playQueueCalls = 0
        override val isQueueEnded: Flow<Boolean> = MutableStateFlow(queueEnded)
        var nextCalls = 0
        override val isPlaying: Flow<Boolean> = MutableStateFlow(true)
        override val isBuffering: Flow<Boolean> = emptyFlow()
        override val currentSongId: Flow<String?> = MutableStateFlow(current.id)
        override val currentSong: Flow<Song?> = MutableStateFlow(current)
        override val currentPosition: Flow<Long> = emptyFlow()
        override val duration: Flow<Long> = emptyFlow()
        override val errors: Flow<PlaybackError> = emptyFlow()
        override val queue: Flow<List<QueueSlot>> = MutableStateFlow(listOf(QueueSlot("slot-0", current)))
        override val currentIndex: Flow<Int> = MutableStateFlow(0)
        override val upcomingQueue: Flow<List<QueueSlot>> = MutableStateFlow(emptyList())
        override val shuffleEnabled: Flow<Boolean> = emptyFlow()
        override val repeatMode: Flow<RepeatMode> = emptyFlow()
        override val hasNext: Flow<Boolean> = emptyFlow()
        override val hasPrevious: Flow<Boolean> = emptyFlow()
        override val isQueueCustomized: Flow<Boolean> = emptyFlow()
        override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) { playQueueCalls++ }
        override suspend fun restoreSavedQueue() {}
        override fun persistPlaybackState() {}
        override fun next() { nextCalls++ }
        override fun previous() {}
        override suspend fun setShuffleEnabled(enabled: Boolean) {}
        override fun setRepeatMode(mode: RepeatMode) {}
        override fun playNext(song: Song): Boolean { playNextCalls++; return acceptsInserts }
        override fun addToQueue(song: Song): Boolean { addToQueueCalls++; return acceptsInserts }
        override fun moveQueueItem(slotId: String, toIndex: Int) {}
        override fun removeQueueItem(slotId: String) {}
        override fun seekToQueueItem(slotId: String) {}
        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
        override suspend fun reconcileWithDatabase() {}
    }

    // Main is replaced around the whole runTest, not inside it: the backgroundScope collectors
    // below are only torn down after the test body returns, and still need Main to do so.
    private fun withViewModel(
        acceptsInserts: Boolean,
        queueEnded: Boolean = false,
        block: TestScope.(PlaybackViewModel, FakeEngine) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = FakeEngine(current, acceptsInserts, queueEnded)
                val viewModel = PlaybackViewModel(PlaybackController(engine))
                // currentSongId/upcomingQueue are WhileSubscribed; hold them the way the UI does,
                // or hasActivePlayback()/the dedup checks would read their stale initial values.
                backgroundScope.launch { viewModel.currentSongId.collect {} }
                backgroundScope.launch { viewModel.upcomingQueue.collect {} }
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun playNextReportsUnavailableWhenTheEngineRefuses() = withViewModel(acceptsInserts = false) { vm, _ ->
        assertEquals(QueueAddResult.Unavailable, vm.playNext(tapped))
        assertEquals(QueueAddResult.Unavailable, vm.playNext(tapped, force = true))
    }

    @Test fun addToQueueReportsUnavailableWhenTheEngineRefuses() = withViewModel(acceptsInserts = false) { vm, _ ->
        assertEquals(QueueAddResult.Unavailable, vm.addToQueue(tapped))
    }

    @Test fun keepQueueAndPlayNextReturnsNullAndDoesNotSkipWhenTheEngineRefuses() =
        withViewModel(acceptsInserts = false) { vm, engine ->
            assertNull(vm.keepQueueAndPlayNext(tapped))
            assertEquals(1, engine.playNextCalls)
            assertEquals(0, engine.nextCalls, "skipping to a song that was never inserted would skip the current one")
        }

    @Test fun acceptedInsertsStillReportAdded() = withViewModel(acceptsInserts = true) { vm, engine ->
        assertEquals(QueueAddResult.Added, vm.playNext(tapped))
        assertEquals(QueueAddResult.Added, vm.addToQueue(tapped))
        assertEquals(true, vm.keepQueueAndPlayNext(song("other")))
        assertEquals(1, engine.nextCalls)
    }

    // A natural end leaves the last song published (the player stays up, paused), so a current
    // song alone no longer means "active" — an ended queue must still take the fresh-play route
    // it took back when the end cleared the current song.
    @Test fun playNextAfterTheQueueEndedStartsFreshPlaybackInsteadOfInsertingIntoIt() =
        withViewModel(acceptsInserts = true, queueEnded = true) { vm, engine ->
            assertEquals(QueueAddResult.Added, vm.playNext(tapped))
            advanceUntilIdle()
            assertEquals(0, engine.playNextCalls, "inserting after an ended queue would not start playback")
            assertEquals(1, engine.playQueueCalls)
        }

    @Test fun addToQueueAfterTheQueueEndedStartsFreshPlaybackInsteadOfAppending() =
        withViewModel(acceptsInserts = true, queueEnded = true) { vm, engine ->
            assertEquals(QueueAddResult.Added, vm.addToQueue(tapped))
            advanceUntilIdle()
            assertEquals(0, engine.addToQueueCalls)
            assertEquals(1, engine.playQueueCalls)
        }

    @Test fun keepQueueAndPlayNextAfterTheQueueEndedStartsFreshPlayback() =
        withViewModel(acceptsInserts = true, queueEnded = true) { vm, engine ->
            // The current (ended) song would otherwise trip the already-queued dedup and no-op.
            assertEquals(true, vm.keepQueueAndPlayNext(current))
            advanceUntilIdle()
            assertEquals(0, engine.playNextCalls)
            assertEquals(1, engine.playQueueCalls)
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
