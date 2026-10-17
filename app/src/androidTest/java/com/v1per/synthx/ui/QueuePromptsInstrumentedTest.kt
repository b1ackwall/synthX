package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.PlaybackViewModel
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
 * M18: the two prompts the shell shows before touching a hand-edited queue, pressed through
 * their real buttons. Replace plays the request; Cancel and Back play nothing. Keep plays the
 * tapped song next; Clear rebuilds from the tapped list; Back does neither.
 */
@RunWith(AndroidJUnit4::class)
class QueuePromptsInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val a = song("a")
    private val b = song("b")
    private val engine = FakePlaybackEngine(song("current"), customized = true)

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() = shell.tearDown()

    private fun text(id: Int) = shell.context.getString(id)

    private fun prompted(trigger: (PlaybackViewModel) -> Unit, title: Int) {
        val viewModel = shell.startShell(engine)
        composeRule.runOnUiThread { trigger(viewModel) }
        shell.awaitText(text(title))
    }

    private fun assertPromptGone(title: Int) =
        composeRule.onNodeWithText(text(title)).assertDoesNotExist()

    // ------------------------------------------------------------ Replace / Cancel (playAll)

    @Test fun replacePromptCancelPlaysNothing() {
        prompted({ it.playAll(listOf(a, b)) }, R.string.queue_replace_prompt_title)
        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        composeRule.waitForIdle()
        assertPromptGone(R.string.queue_replace_prompt_title)
        assertEquals(emptyList<PlayCall>(), engine.playQueueCalls.toList())
    }

    @Test fun replacePromptBackPlaysNothing() {
        prompted({ it.playAll(listOf(a, b)) }, R.string.queue_replace_prompt_title)
        shell.pressBackKey()
        assertPromptGone(R.string.queue_replace_prompt_title)
        assertEquals(emptyList<PlayCall>(), engine.playQueueCalls.toList())
    }

    @Test fun replacePromptReplacePlaysTheRequestOnce() {
        prompted({ it.playAll(listOf(a, b)) }, R.string.queue_replace_prompt_title)
        composeRule.onNodeWithText(text(R.string.action_replace)).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { engine.playQueueCalls.isNotEmpty() }
        composeRule.waitForIdle()
        assertPromptGone(R.string.queue_replace_prompt_title)
        assertEquals(listOf(PlayCall(listOf(a, b), 0, false)), engine.playQueueCalls.toList())
    }

    // ------------------------------------------------------------ Keep / Clear (song tap)

    @Test fun queuePromptKeepPlaysTheTappedSongNext() {
        prompted({ it.requestPlayQueue(listOf(a, b), 1) }, R.string.queue_prompt_title)
        composeRule.onNodeWithText(text(R.string.action_keep)).performClick()
        shell.awaitText(text(R.string.actions_playing_next))
        assertPromptGone(R.string.queue_prompt_title)
        assertEquals(listOf(b), engine.playNextCalls.toList())
        assertEquals(1, engine.nextCalls.get())
        assertEquals(emptyList<PlayCall>(), engine.playQueueCalls.toList())
    }

    @Test fun queuePromptClearRebuildsFromTheTappedList() {
        prompted({ it.requestPlayQueue(listOf(a, b), 1) }, R.string.queue_prompt_title)
        composeRule.onNodeWithText(text(R.string.action_clear)).performClick()
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { engine.playQueueCalls.isNotEmpty() }
        composeRule.waitForIdle()
        assertPromptGone(R.string.queue_prompt_title)
        assertEquals(listOf(PlayCall(listOf(a, b), 1, null)), engine.playQueueCalls.toList())
        assertEquals(emptyList<Song>(), engine.playNextCalls.toList())
        assertEquals(0, engine.nextCalls.get())
    }

    @Test fun queuePromptBackDoesNothing() {
        prompted({ it.requestPlayQueue(listOf(a, b), 1) }, R.string.queue_prompt_title)
        shell.pressBackKey()
        assertPromptGone(R.string.queue_prompt_title)
        assertEquals(emptyList<PlayCall>(), engine.playQueueCalls.toList())
        assertEquals(emptyList<Song>(), engine.playNextCalls.toList())
        assertEquals(0, engine.nextCalls.get())
    }
}
