package com.v1per.synthx.playback

import com.v1per.synthx.playback.QueueModelEngine.Companion.song
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertNull

/**
 * The pending Keep/Clear and Replace prompts and the two duplicate confirmations live in the view
 * model, so they outlast the composition that shows them. A fresh play clears both prompts, a newer
 * prompt takes the place of an older one, and every answer reads the queue as it stands when the
 * answer is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackViewModelPendingPromptTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private fun withViewModel(block: TestScope.(PlaybackViewModel, QueueModelEngine) -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val engine = QueueModelEngine()
                engine.install(listOf(c, a, b), 0)
                engine.customized.value = true
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
    private val y = song("y")
    private val tapped = listOf(a, x, y)

    @Test fun aPendingSongTapPromptOutlivesItsCollector() = withViewModel { vm, _ ->
        vm.requestPlayQueue(tapped, 1)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.queuePrompt.collect {} }
        advanceUntilIdle()
        first.cancel()
        advanceUntilIdle()

        val seen = mutableListOf<QueuePromptEvent?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.queuePrompt.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(listOf<QueuePromptEvent?>(QueuePromptEvent(x, tapped, 1)), seen)
    }

    @Test fun aFreshPlayClearsBothPendingPrompts() = withViewModel { vm, _ ->
        vm.requestPlayQueue(tapped, 1)
        vm.playQueue(listOf(b), 0)
        assertNull(vm.queuePrompt.value)

        vm.playAll(tapped)
        vm.playQueue(listOf(b), 0)
        assertNull(vm.queueReplacePrompt.value)
    }

    @Test fun aNewerPromptTakesThePlaceOfTheOlder() = withViewModel { vm, _ ->
        vm.requestPlayQueue(tapped, 1)
        vm.requestPlayQueue(tapped, 2)
        assertEquals(y, vm.queuePrompt.value?.tappedSong)

        vm.playAll(tapped)
        assertNull(vm.queuePrompt.value, "only one queue question is open at a time")
        assertEquals(PlayAllRequest(tapped, 0, false), vm.queueReplacePrompt.value?.request)

        vm.requestPlayQueue(tapped, 0)
        assertNull(vm.queueReplacePrompt.value)
        assertEquals(a, vm.queuePrompt.value?.tappedSong)
    }

    @Test fun dismissingClosesThePromptAndPlaysNothing() = withViewModel { vm, engine ->
        vm.requestPlayQueue(tapped, 1)
        vm.dismissQueuePrompt()
        vm.playAll(tapped)
        vm.dismissQueueReplacePrompt()
        advanceUntilIdle()

        assertNull(vm.queuePrompt.value)
        assertNull(vm.queueReplacePrompt.value)
        assertEquals(emptyList(), engine.calls)
    }

    @Test fun clearClosesThePromptAndPlaysTheTappedList() = withViewModel { vm, engine ->
        vm.requestPlayQueue(tapped, 1)
        vm.answerClear()
        advanceUntilIdle()

        assertNull(vm.queuePrompt.value)
        assertEquals("playQueue:[a, x, y]@1", engine.calls.first())
    }

    @Test fun replaceClosesThePromptAndPlaysTheRequest() = withViewModel { vm, engine ->
        vm.playAll(tapped)
        vm.answerReplace()
        advanceUntilIdle()

        assertNull(vm.queueReplacePrompt.value)
        assertEquals("playQueue:[a, x, y]@0", engine.calls.first())
    }

    @Test fun keepClosesThePromptAndASecondKeepDoesNothing() = withViewModel { vm, engine ->
        vm.requestPlayQueue(tapped, 1)

        assertEquals(true, vm.answerKeep())
        assertNull(vm.queuePrompt.value)
        assertNull(vm.answerKeep(), "nothing is pending any more")
        vm.answerClear()
        vm.answerReplace()
        advanceUntilIdle()

        assertEquals(listOf("playNext:x", "next", "resume"), engine.calls)
    }

    @Test fun keepReadsTheQueueAtAnswerTime() = withViewModel { vm, engine ->
        vm.requestPlayQueue(tapped, 1)
        engine.install(listOf(c, x, a), 0)
        engine.customized.value = true
        advanceUntilIdle()

        assertEquals(true, vm.answerKeep())

        assertEquals(listOf("c", "x", "a"), engine.songIds(), "x became next after the prompt was raised")
        assertEquals(listOf("next", "resume"), engine.calls)
    }

    @Test fun keepAfterTheQueueEndedWhileThePromptWasOpenPlaysTheTappedList() = withViewModel { vm, engine ->
        vm.requestPlayQueue(tapped, 1)
        engine.ended.value = true
        advanceUntilIdle()

        assertEquals(true, vm.answerKeep())
        advanceUntilIdle()

        assertEquals("playQueue:[a, x, y]@1", engine.calls.first())
    }

    @Test fun theDuplicateConfirmationsAreHeldUntilTakenOrDismissed() = withViewModel { vm, _ ->
        val confirmations = vm.queueDuplicateConfirmations

        confirmations.askPlayNext(x)
        confirmations.askAddToQueue(y)
        assertEquals(x, confirmations.playNext.value)
        assertEquals(y, confirmations.addToQueue.value)

        assertEquals(x, confirmations.takePlayNext())
        assertNull(confirmations.playNext.value)
        assertNull(confirmations.takePlayNext(), "a second confirm finds nothing")

        confirmations.dismissAddToQueue()
        assertNull(confirmations.addToQueue.value)
        assertNull(confirmations.takeAddToQueue())
    }
}
