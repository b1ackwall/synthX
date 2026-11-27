package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Play Next, Add to Queue and the Keep fallback while the saved-queue restore is still pending:
 * they answer at once, wait for the restore, then apply to the restored queue (skipping a song that
 * already sits in the requested spot) or, when the restore produced no queue, run the fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelRestoreWaitTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    /**
     * An engine whose restore waits for [release] and then installs [restores], if set, with
     * [restoresCustomized] and [restoresModes]. With [installOnPlay] a fresh play installs its own
     * songs; a play waits for [loadGate] first, when set, and records "cancelled:" when it is cut off.
     * Play Next and Add to Queue mark a queue customised, as the real engine does.
     */
    internal class GatedRestoreEngine : PlaybackEngine {
        val release = CompletableDeferred<Unit>()
        var restores: Pair<List<Song>, Int>? = null
        var restoresCustomized = false
        var restoresModes: Pair<Boolean, RepeatMode>? = null
        var installOnPlay = false
        var loadGate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<String>()

        /** Each fresh play's start index and the shuffle and repeat modes it found, in call order. */
        val plays = mutableListOf<Triple<Int, Boolean, RepeatMode>>()
        val songId = MutableStateFlow<String?>(null)
        val customized = MutableStateFlow(false)
        val shuffle = MutableStateFlow(false)
        val repeat = MutableStateFlow(RepeatMode.Off)
        private val queueFlow = MutableStateFlow<List<QueueSlot>>(emptyList())
        private val upcoming = MutableStateFlow<List<QueueSlot>>(emptyList())

        fun install(songs: List<Song>, current: Int) {
            val slots = songs.mapIndexed { i, song -> QueueSlot("slot-$i", song) }
            queueFlow.value = slots
            upcoming.value = slots.drop(current + 1)
            songId.value = songs[current].id
        }

        override val isQueueEnded: Flow<Boolean> = MutableStateFlow(false)
        override val isPlaying: Flow<Boolean> = MutableStateFlow(false)
        override val isBuffering: Flow<Boolean> = emptyFlow()
        override val currentSongId: Flow<String?> = songId
        override val currentSong: Flow<Song?> = MutableStateFlow(null)
        override val currentPosition: Flow<Long> = emptyFlow()
        override val duration: Flow<Long> = emptyFlow()
        override val errors: Flow<PlaybackError> = emptyFlow()
        override val queue: Flow<List<QueueSlot>> = queueFlow
        override val currentIndex: Flow<Int> = MutableStateFlow(0)
        override val upcomingQueue: Flow<List<QueueSlot>> = upcoming
        override val shuffleEnabled: Flow<Boolean> = shuffle
        override val shuffleReorderedByUser: Flow<Boolean> = emptyFlow()
        override val repeatMode: Flow<RepeatMode> = repeat
        override val hasNext: Flow<Boolean> = emptyFlow()
        override val hasPrevious: Flow<Boolean> = emptyFlow()
        override val isQueueCustomized: Flow<Boolean> = customized
        override val queueGeneration: StateFlow<Long> = MutableStateFlow(0L)
        override suspend fun playQueue(songs: List<Song>, startIndex: Int, shuffle: Boolean?) {
            val ids = songs.map { it.id }
            calls += "playQueue:$ids"
            plays += Triple(startIndex, this.shuffle.value, repeat.value)
            try {
                loadGate?.await()
            } catch (e: CancellationException) {
                calls += "cancelled:$ids"
                throw e
            }
            if (installOnPlay) {
                install(songs, startIndex)
                customized.value = false
            }
        }
        override suspend fun restoreSavedQueue() {
            release.await()
            restores?.let { (songs, current) ->
                install(songs, current)
                customized.value = restoresCustomized
                restoresModes?.let { (shuffleOn, mode) ->
                    shuffle.value = shuffleOn
                    repeat.value = mode
                }
            }
        }
        override fun persistPlaybackState() {}
        override fun next() {
            calls += "next"
        }
        override fun previous() {}
        override suspend fun setShuffleEnabled(enabled: Boolean) {}
        override fun setRepeatMode(mode: RepeatMode) {}
        override fun playNext(song: Song): Boolean {
            calls += "playNext:${song.id}"
            customized.value = true
            return true
        }
        override fun addToQueue(song: Song): Boolean {
            calls += "addToQueue:${song.id}"
            customized.value = true
            return true
        }
        override fun moveQueueItem(slotId: String, toIndex: Int) {}
        override fun removeQueueItem(slotId: String) {}
        override fun seekToQueueItem(slotId: String) {}
        override fun pause() {}
        override fun resume() {
            calls += "resume"
        }
        override fun currentRepeatMode(): RepeatMode = repeat.value
        override fun seekTo(positionMs: Long) {}
        override fun release() {}
        override suspend fun reconcileWithDatabase() {}
    }

    private fun withViewModel(block: TestScope.(PlaybackViewModel, GatedRestoreEngine) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = GatedRestoreEngine()
                val viewModel = PlaybackViewModel(PlaybackController(engine))
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
    private val x = song("x")
    private val y = song("y")

    private fun prompt(song: Song, context: List<Song> = listOf(song)) =
        QueuePromptEvent(song, context, context.indexOf(song))

    @Test fun playNextWaitsForThePendingRestoreThenInsertsIntoTheRestoredQueue() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()

        assertEquals(QueueAddResult.Added, vm.playNext(x))
        runCurrent()
        assertEquals(emptyList(), engine.calls, "nothing may be decided before the restore finishes")

        engine.restores = listOf(a, b, c) to 1
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playNext:x"), engine.calls)
    }

    @Test fun addToQueueWaitsForThePendingRestoreThenAppends() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()

        assertEquals(QueueAddResult.Added, vm.addToQueue(x))
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        engine.restores = listOf(a, b, c) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("addToQueue:x"), engine.calls)
    }

    @Test fun theKeepFallbackWaitsThenKeepsTheRestoredQueue() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()

        assertEquals(true, vm.keepQueueAndPlayNext(prompt(x)))
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        engine.restores = listOf(a, b) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
    }

    @Test fun aPlayNextOfTheSongAlreadyNextIsSkipped() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        assertEquals(QueueAddResult.Added, vm.playNext(x))

        engine.restores = listOf(a, x, b) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), engine.calls, "x already sits right after the current song")
    }

    @Test fun anAddToQueueOfTheSongAlreadyLastIsSkipped() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        assertEquals(QueueAddResult.Added, vm.addToQueue(x))

        engine.restores = listOf(a, b, x) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), engine.calls, "x already sits at the end of the queue")
    }

    @Test fun aKeepOfASongAlreadyInUpNextInsertsACopyAfterTheCurrentSong() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        assertEquals(true, vm.keepQueueAndPlayNext(prompt(x)))

        engine.restores = listOf(a, b, x, c) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls, "x later in Up Next is not next")
    }

    @Test fun aPlayNextOfADifferentSongIsInsertedEvenWhenTheNextSlotIsTaken() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.playNext(y)

        engine.restores = listOf(a, x, b) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playNext:y"), engine.calls)
    }

    @Test fun aRestoreThatProducesNoQueueRunsThePlayNextFallbackAfterIt() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.playNext(x)
        runCurrent()
        assertEquals(emptyList(), engine.calls, "the fallback must wait for the restore too")

        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[x]"), engine.calls)
    }

    @Test fun aRestoreThatProducesNoQueueRunsTheAddToQueueFallbackAfterIt() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.addToQueue(y)
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[y]"), engine.calls)
    }

    @Test fun aRestoreThatProducesNoQueueRunsTheKeepFallbackAfterIt() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        assertTrue(vm.keepQueueAndPlayNext(prompt(a, listOf(b, a, c))) == true)
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("playQueue:[b, a, c]"), engine.calls)
    }

    @Test fun actionsTakenDuringTheRestoreApplyInTheOrderTheyWereTaken() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        runCurrent()
        vm.addToQueue(y)
        vm.playNext(x)

        engine.restores = listOf(a, b) to 0
        engine.release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("addToQueue:y", "playNext:x"), engine.calls)
    }

    @Test fun withNoRestoreRequestedNothingWaits() = withViewModel { vm, engine ->
        vm.playNext(x)
        runCurrent()

        assertEquals(listOf("playQueue:[x]"), engine.calls)
    }

    @Test fun afterTheRestoreFinishedNothingWaits() = withViewModel { vm, engine ->
        vm.restoreSavedQueue()
        engine.release.complete(Unit)
        advanceUntilIdle()

        vm.playNext(x)
        runCurrent()

        assertEquals(listOf("playQueue:[x]"), engine.calls)
    }

    @Test fun activePlaybackNeverWaitsForARestore() = withViewModel { vm, engine ->
        engine.install(listOf(a, b), 0)
        advanceUntilIdle()
        vm.restoreSavedQueue()
        runCurrent()

        assertEquals(QueueAddResult.Added, vm.playNext(x))

        assertEquals(listOf("playNext:x"), engine.calls, "a playing queue answers at once")
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
