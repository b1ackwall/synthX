package com.v1per.synthx.playback

import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.QueueModelEngine.Companion.song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Keep, the answer to the Keep/Clear prompt: against an active queue the tapped song plays right
 * after the current slot (a new copy, unless that song is already exactly next) and playback starts;
 * with nothing left to keep, the whole list it was tapped from plays, as Clear would play it. A Keep
 * held by a pending restore or queue load applies the same rules to the queue it finds afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelKeepRulesTest {
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
    private val d = song("d")
    private val x = song("x")
    private val p = song("p")
    private val q = song("q")
    private val r = song("r")
    private val context = listOf(p, q, x, r)

    private fun prompt(song: Song = x) = QueuePromptEvent(song, context, context.indexOf(song).coerceAtLeast(0))

    private fun keep(vm: PlaybackViewModel, prompt: QueuePromptEvent): Boolean? = vm.keepQueueAndPlayNext(prompt)

    /** The engine paused on [songs] at [current]: a hand-edited queue the prompt would ask about. */
    private fun TestScope.paused(engine: QueueModelEngine, songs: List<Song>, current: Int = 0) {
        engine.install(songs, current)
        engine.customized.value = true
        advanceUntilIdle()
    }

    @Test fun aKeepOnAPausedPlayerMovesToTheSongAndThenPlays() = withViewModel { vm, engine ->
        paused(engine, listOf(c, a, b))

        assertEquals(true, keep(vm, prompt()))

        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
        assertTrue(engine.playing.value)
    }

    @Test fun aSongDeepInUpNextGetsACopyAfterTheCurrentSongAndNothingIsSkipped() = withViewModel { vm, engine ->
        paused(engine, listOf(c, a, b, x, d))

        assertEquals(true, keep(vm, prompt()))

        assertEquals(listOf("c", "x", "a", "b", "x", "d"), engine.songIds())
        assertEquals(1, engine.currentIndex())
        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
    }

    @Test fun theSongExactlyNextIsPlayedWhereItIsWithoutACopy() = withViewModel { vm, engine ->
        paused(engine, listOf(c, x, a))
        val slots = engine.slotIds()

        assertEquals(true, keep(vm, prompt()))

        assertEquals(slots, engine.slotIds(), "nothing is inserted")
        assertEquals(slots[1], engine.currentSlotId(), "the existing next slot plays")
        assertEquals(listOf("next", "resume"), engine.calls)
    }

    @Test fun theCurrentSongGetsACopyRightAfterItAndRestartsOnIt() = withViewModel { vm, engine ->
        paused(engine, listOf(x, a, b))
        val history = engine.slotIds().first()

        assertEquals(true, keep(vm, prompt()))

        assertEquals(listOf("x", "x", "a", "b"), engine.songIds())
        assertEquals(1, engine.currentIndex())
        assertEquals(history, engine.slotIds().first(), "the earlier copy stays in history")
        assertNotEquals(history, engine.currentSlotId())
        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
    }

    @Test fun copiesInHistoryAndLaterInUpNextDoNotChangeTheRule() = withViewModel { vm, engine ->
        paused(engine, listOf(x, c, a, x), current = 1)

        assertEquals(true, keep(vm, prompt()))

        assertEquals(listOf("x", "c", "x", "a", "x"), engine.songIds())
        assertEquals(2, engine.currentIndex())
    }

    @Test fun keepOnTheLastSongOfTheQueueInsertsAndPlaysIt() = withViewModel { vm, engine ->
        paused(engine, listOf(a, c), current = 1)

        assertEquals(true, keep(vm, prompt()))

        assertEquals(listOf("a", "c", "x"), engine.songIds())
        assertEquals(2, engine.currentIndex())
        assertTrue(engine.playing.value)
    }

    @Test fun aRefusedInsertReturnsNullAndNeitherMovesNorPlays() = withViewModel { vm, engine ->
        paused(engine, listOf(c, a))
        engine.acceptsInserts = false

        assertNull(keep(vm, prompt()))

        assertEquals(listOf("playNext:x"), engine.calls)
        assertEquals(0, engine.currentIndex())
    }

    @Test fun keepAfterTheQueueEndedPlaysTheWholeTappedListLikeClear() = withViewModel { vm, engine ->
        paused(engine, listOf(c, a), current = 1)
        engine.ended.value = true
        advanceUntilIdle()

        assertEquals(true, keep(vm, prompt()))
        advanceUntilIdle()

        val keepCalls = engine.calls.toList()
        engine.calls.clear()
        vm.clearQueueAndPlay(context, 2)
        advanceUntilIdle()
        assertEquals(engine.calls, keepCalls, "Keep with nothing to keep plays exactly what Clear plays")
        assertEquals("playQueue:[p, q, x, r]@2", keepCalls.first())
    }

    @Test fun aHeldKeepAfterARestoreFollowsTheKeepRules() = withViewModel { vm, engine ->
        val restore = CompletableDeferred<Unit>()
        engine.restoreGate = restore
        engine.restoreInstalls = listOf(a, b, x, c) to 0
        vm.restoreSavedQueue()
        runCurrent()

        assertEquals(true, keep(vm, prompt()))
        runCurrent()
        assertEquals(emptyList(), engine.calls)

        restore.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "x", "b", "x", "c"), engine.songIds())
        assertEquals(1, engine.currentIndex())
        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
    }

    @Test fun aHeldKeepOfTheSongExactlyNextAfterARestoreMovesWithoutACopy() = withViewModel { vm, engine ->
        val restore = CompletableDeferred<Unit>()
        engine.restoreGate = restore
        engine.restoreInstalls = listOf(a, x, b) to 0
        vm.restoreSavedQueue()
        runCurrent()
        keep(vm, prompt())

        restore.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a", "x", "b"), engine.songIds())
        assertEquals(listOf("next", "resume"), engine.calls)
    }

    @Test fun aHeldKeepOfTheCurrentSongAfterARestorePlaysACopy() = withViewModel { vm, engine ->
        val restore = CompletableDeferred<Unit>()
        engine.restoreGate = restore
        engine.restoreInstalls = listOf(x, a, b) to 0
        vm.restoreSavedQueue()
        runCurrent()
        keep(vm, prompt())

        restore.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("x", "x", "a", "b"), engine.songIds())
        assertEquals(1, engine.currentIndex())
        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
    }

    @Test fun aHeldKeepWhoseRestoreBringsNoQueuePlaysTheTappedList() = withViewModel { vm, engine ->
        val restore = CompletableDeferred<Unit>()
        engine.restoreGate = restore
        vm.restoreSavedQueue()
        runCurrent()
        keep(vm, prompt())

        restore.complete(Unit)
        advanceUntilIdle()
        assertEquals("playQueue:[p, q, x, r]@2", engine.calls.first())
        assertEquals(listOf("p", "q", "x", "r"), engine.songIds())
        assertEquals(2, engine.currentIndex())
    }

    @Test fun aKeepDuringALoadAppliesToTheNewQueue() = withViewModel { vm, engine ->
        paused(engine, listOf(c, a))
        val load = CompletableDeferred<Unit>()
        engine.loadGates.addLast(load)
        vm.playQueue(listOf(b, d), 0)
        runCurrent()

        assertEquals(true, keep(vm, prompt()))
        runCurrent()
        assertFalse("playNext:x" in engine.calls)

        load.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("b", "x", "d"), engine.songIds())
        assertEquals(1, engine.currentIndex())
        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls.takeLast(3))
    }

    @Test fun aKeepDuringALoadThatInstallsNothingPlaysTheTappedList() = withViewModel { vm, engine ->
        engine.loadsInstall = false
        val load = CompletableDeferred<Unit>()
        engine.loadGates.addLast(load)
        vm.playQueue(listOf(b, d), 0)
        runCurrent()
        keep(vm, prompt())

        load.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("playQueue:[b, d]@0", "playQueue:[p, q, x, r]@2"), engine.calls)
    }

    @Test fun playNextAndAddToQueueFallbacksStayOneSong() = withViewModel { vm, engine ->
        vm.playNext(x)
        vm.addToQueue(a)
        advanceUntilIdle()

        assertEquals("playQueue:[x]@0", engine.calls.first())
        assertTrue(engine.calls.none { it.startsWith("playQueue:[p") })
    }
}
