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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The customised-queue prompts and the Play/Shuffle entry points. Nothing here subscribes to
 * [PlaybackViewModel.currentSongId], which is what the playlist detail screen looks like from the
 * ViewModel's side: the prompts must not depend on which screen happens to collect it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelQueuePromptTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val current = song("current")

    private data class PlayCall(val songs: List<Song>, val startIndex: Int, val shuffle: Boolean?)

    /** Always returns [index] from nextInt(until), so the shuffled start song is known. */
    private class FixedRandom(private val index: Int) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = index
    }

    private class FakeEngine(current: Song, customized: Boolean) : PlaybackEngine {
        val playQueueCalls = mutableListOf<PlayCall>()
        var setShuffleCalls = 0
        override val isQueueEnded: Flow<Boolean> = MutableStateFlow(false)
        override val isPlaying: Flow<Boolean> = MutableStateFlow(true)
        override val isBuffering: Flow<Boolean> = emptyFlow()
        val songId = MutableStateFlow<String?>(current.id)
        override val currentSongId: Flow<String?> = songId
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
        override val isQueueCustomized: Flow<Boolean> = MutableStateFlow(customized)
        override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
            playQueueCalls += PlayCall(songs, startIndex, shuffle)
        }
        override suspend fun restoreSavedQueue() {}
        override fun persistPlaybackState() {}
        override fun next() {}
        override fun previous() {}
        override suspend fun setShuffleEnabled(enabled: Boolean) {
            setShuffleCalls++
        }
        override fun setRepeatMode(mode: RepeatMode) {}
        override fun playNext(song: Song): Boolean = true
        override fun addToQueue(song: Song): Boolean = true
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

    private fun withViewModel(
        customized: Boolean = true,
        random: Random = FixedRandom(0),
        block: TestScope.(PlaybackViewModel, FakeEngine) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = FakeEngine(current, customized)
                val viewModel = PlaybackViewModel(PlaybackController(engine), random = random)
                advanceUntilIdle()
                block(viewModel, engine)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun songTapPromptsForACustomizedQueueWithNoCurrentSongIdSubscriber() = withViewModel { vm, engine ->
        val prompts = mutableListOf<QueuePromptEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.queuePrompts.collect { prompts += it }
        }
        advanceUntilIdle()

        vm.requestPlayQueue(listOf(song("a"), song("b")), 1)
        advanceUntilIdle()

        assertEquals(1, prompts.size, "a customized, playing queue must be asked about")
        assertEquals(0, engine.playQueueCalls.size, "the hand-edited queue was silently replaced")
    }

    private fun TestScope.replacePrompts(vm: PlaybackViewModel): List<QueueReplacePromptEvent> {
        val prompts = mutableListOf<QueueReplacePromptEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.queueReplacePrompts.collect { prompts += it }
        }
        return prompts
    }

    private val a = song("a")
    private val b = song("b")
    private val c = song("c")
    private val gone = song("gone", missing = true)

    @Test fun playAllPlaysThePlayableSongsFromTheFirstWithShuffleOff() = withViewModel(
        customized = false,
    ) { vm, engine ->
        vm.playAll(listOf(gone, a, b))
        advanceUntilIdle()
        assertEquals(listOf(PlayCall(listOf(a, b), 0, false)), engine.playQueueCalls)
        assertEquals(0, engine.setShuffleCalls, "the flag travels with playQueue, never as its own call")
    }

    @Test fun shuffleAllStartsOnTheRandomIndexWithShuffleOn() =
        withViewModel(customized = false, random = FixedRandom(2)) { vm, engine ->
            vm.shuffleAll(listOf(a, gone, b, c))
            advanceUntilIdle()
            assertEquals(listOf(PlayCall(listOf(a, b, c), 2, true)), engine.playQueueCalls)
            assertEquals(0, engine.setShuffleCalls)
        }

    @Test fun emptyAndAllMissingListsDoNothing() = withViewModel { vm, engine ->
        val prompts = replacePrompts(vm)
        vm.playAll(emptyList())
        vm.shuffleAll(emptyList())
        vm.playAll(listOf(gone))
        vm.shuffleAll(listOf(gone))
        advanceUntilIdle()
        assertEquals(emptyList(), engine.playQueueCalls)
        assertEquals(emptyList(), prompts)
    }

    @Test fun aCustomizedPlayingQueueIsAskedAboutAndOnlyReplacedOnReplace() =
        withViewModel(random = FixedRandom(1)) { vm, engine ->
            val prompts = replacePrompts(vm)
            vm.shuffleAll(listOf(a, b, gone, c))
            advanceUntilIdle()
            assertEquals(listOf(QueueReplacePromptEvent(PlayAllRequest(listOf(a, b, c), 1, true))), prompts)
            assertEquals(emptyList(), engine.playQueueCalls, "nothing plays before Replace")

            // What MainScaffold's Replace does with the event.
            val request = prompts.single().request
            vm.playQueue(request.songs, request.startIndex, request.shuffle)
            advanceUntilIdle()
            assertEquals(listOf(PlayCall(listOf(a, b, c), 1, true)), engine.playQueueCalls)
        }

    // currentSongId is lazy: after a collector leaves, it keeps the last value it saw. The
    // already-queued check must read the live current song regardless.
    private fun TestScope.collectCurrentSongIdBriefly(vm: PlaybackViewModel) {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.currentSongId.collect {} }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
    }

    @Test fun keepRecognisesTheCurrentSongAfterItChangedWhileNothingCollectedCurrentSongId() = withViewModel {
            vm,
            engine,
        ->
        collectCurrentSongIdBriefly(vm)
        engine.songId.value = "x"
        advanceUntilIdle()

        assertEquals(false, vm.keepQueueAndPlayNext(song("x")), "the current song was treated as not queued")
    }

    @Test fun keepDoesNotRejectASongThatWasCurrentOnlyWhenCurrentSongIdWasLastCollected() = withViewModel {
            vm,
            engine,
        ->
        collectCurrentSongIdBriefly(vm)
        assertEquals("current", vm.currentSongId.value, "the lazy copy must hold the earlier value")
        engine.songId.value = "z"
        advanceUntilIdle()

        assertEquals(true, vm.keepQueueAndPlayNext(current), "a song that is not queued was rejected as queued")
    }

    @Test fun keepRecognisesTheCurrentSongWhenCurrentSongIdWasNeverCollected() = withViewModel { vm, _ ->
        assertEquals(false, vm.keepQueueAndPlayNext(current))
    }

    private fun song(id: String, missing: Boolean = false) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        duration = 60_000L,
        filePath = "file:///$id.mp3",
        isMissing = missing,
    )
}
