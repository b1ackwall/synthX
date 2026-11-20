package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.ui.components.ADD_TO_QUEUE_DUPLICATE_CANCEL_BUTTON_TAG
import com.v1per.synthx.ui.components.ADD_TO_QUEUE_DUPLICATE_CONFIRM_BUTTON_TAG
import com.v1per.synthx.ui.components.ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG
import com.v1per.synthx.ui.components.PLAY_NEXT_DUPLICATE_CONFIRM_BUTTON_TAG
import com.v1per.synthx.ui.components.PLAY_NEXT_DUPLICATE_DIALOG_TAG
import com.v1per.synthx.ui.components.QUEUE_PROMPT_CLEAR_BUTTON_TAG
import com.v1per.synthx.ui.components.QUEUE_PROMPT_DIALOG_TAG
import com.v1per.synthx.ui.components.QUEUE_PROMPT_KEEP_BUTTON_TAG
import com.v1per.synthx.ui.components.QUEUE_REPLACE_CANCEL_BUTTON_TAG
import com.v1per.synthx.ui.components.QUEUE_REPLACE_CONFIRM_BUTTON_TAG
import com.v1per.synthx.ui.components.QUEUE_REPLACE_DIALOG_TAG
import com.v1per.synthx.ui.testing.FakePlaybackEngine
import com.v1per.synthx.ui.testing.FakePlaybackEngine.Companion.song
import com.v1per.synthx.ui.testing.FakePlaybackEngine.PlayCall
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each of the four queue questions (Keep or Clear, Replace, and the Play Next and Add to Queue
 * duplicate confirmations) is still on screen after the activity is recreated, as a dark-mode,
 * locale, font-scale or window-size change recreates it, and its buttons still do what they did.
 */
@RunWith(AndroidJUnit4::class)
class QueuePromptRecreationInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val a = song("a")
    private val b = song("b")
    private val x = song("x")
    private val engine = FakePlaybackEngine(song("current"), customized = true)

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun text(id: Int) = shell.context.getString(id)

    private fun awaitTag(tag: String) = composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.tagCount(tag) > 0 }

    /** Raises a question, recreates the activity, shows the shell again and waits for [dialogTag]. */
    private fun raisedThenRecreated(raise: (PlaybackViewModel) -> Unit, dialogTag: String) {
        val viewModel = shell.startShell(engine)
        composeRule.runOnUiThread { raise(viewModel) }
        awaitTag(dialogTag)

        composeRule.activityRule.scenario.recreate()
        shell.startShell(engine)
        awaitTag(dialogTag)
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
    }

    @Test fun theKeepOrClearQuestionSurvivesAndKeepStillPlaysTheSongNext() {
        raisedThenRecreated({ it.requestPlayQueue(listOf(a, b), 1) }, QUEUE_PROMPT_DIALOG_TAG)

        click(QUEUE_PROMPT_KEEP_BUTTON_TAG)
        shell.awaitText(text(R.string.actions_playing_next))

        assertEquals(0, shell.tagCount(QUEUE_PROMPT_DIALOG_TAG))
        assertEquals(listOf(b), engine.playNextCalls.toList())
        assertEquals(1, engine.nextCalls.get())
        assertEquals(1, engine.resumeCalls.get())
        assertEquals(emptyList<PlayCall>(), engine.playQueueCalls.toList())
    }

    @Test fun theKeepOrClearQuestionSurvivesAndClearStillPlaysTheTappedList() {
        raisedThenRecreated({ it.requestPlayQueue(listOf(a, b), 1) }, QUEUE_PROMPT_DIALOG_TAG)

        click(QUEUE_PROMPT_CLEAR_BUTTON_TAG)
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { engine.playQueueCalls.isNotEmpty() }

        assertEquals(0, shell.tagCount(QUEUE_PROMPT_DIALOG_TAG))
        assertEquals(listOf(PlayCall(listOf(a, b), 1, null)), engine.playQueueCalls.toList())
    }

    @Test fun theReplaceQuestionSurvivesAndReplaceStillPlaysTheRequest() {
        raisedThenRecreated({ it.playAll(listOf(a, b)) }, QUEUE_REPLACE_DIALOG_TAG)

        click(QUEUE_REPLACE_CONFIRM_BUTTON_TAG)
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { engine.playQueueCalls.isNotEmpty() }

        assertEquals(0, shell.tagCount(QUEUE_REPLACE_DIALOG_TAG))
        assertEquals(listOf(PlayCall(listOf(a, b), 0, false)), engine.playQueueCalls.toList())
    }

    @Test fun theReplaceQuestionSurvivesAndCancelStillPlaysNothing() {
        raisedThenRecreated({ it.playAll(listOf(a, b)) }, QUEUE_REPLACE_DIALOG_TAG)

        click(QUEUE_REPLACE_CANCEL_BUTTON_TAG)

        assertEquals(0, shell.tagCount(QUEUE_REPLACE_DIALOG_TAG))
        assertEquals(emptyList<PlayCall>(), engine.playQueueCalls.toList())
    }

    @Test fun thePlayNextDuplicateQuestionSurvivesAndConfirmStillAddsTheCopy() {
        raisedThenRecreated({ it.queueDuplicateConfirmations.askPlayNext(x) }, PLAY_NEXT_DUPLICATE_DIALOG_TAG)

        click(PLAY_NEXT_DUPLICATE_CONFIRM_BUTTON_TAG)
        shell.awaitText(text(R.string.actions_playing_next))

        assertEquals(0, shell.tagCount(PLAY_NEXT_DUPLICATE_DIALOG_TAG))
        assertEquals(listOf(x), engine.playNextCalls.toList())
    }

    @Test fun theAddToQueueDuplicateQuestionSurvivesAndConfirmStillAddsTheCopy() {
        raisedThenRecreated({ it.queueDuplicateConfirmations.askAddToQueue(x) }, ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG)

        click(ADD_TO_QUEUE_DUPLICATE_CONFIRM_BUTTON_TAG)
        shell.awaitText(text(R.string.actions_added_to_queue))

        assertEquals(0, shell.tagCount(ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG))
        assertEquals(listOf(x), engine.addToQueueCalls.toList())
    }

    @Test fun theAddToQueueDuplicateQuestionSurvivesAndCancelAddsNothing() {
        raisedThenRecreated({ it.queueDuplicateConfirmations.askAddToQueue(x) }, ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG)

        click(ADD_TO_QUEUE_DUPLICATE_CANCEL_BUTTON_TAG)

        assertEquals(0, shell.tagCount(ADD_TO_QUEUE_DUPLICATE_DIALOG_TAG))
        assertEquals(emptyList<Any>(), engine.addToQueueCalls.toList())
    }
}
