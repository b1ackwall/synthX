package com.v1per.synthx.playback

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.ui.player.QueueContentTestHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real engine at the last slot of a three-song queue: Up Next stays empty under every repeat
 * mode, and the text in its place follows the mode the engine reports, changing as soon as the mode
 * is changed on the engine.
 */
@RunWith(AndroidJUnit4::class)
class UpNextRepeatMarkerInstrumentedTest : PlaybackServiceTestBase() {
    @get:Rule val composeRule = createComposeRule()

    private val nothingQueued by lazy { context.getString(R.string.empty_queue_no_upcoming) }
    private val repeatAll by lazy { context.getString(R.string.empty_queue_repeat_all) }
    private val repeatOne by lazy { context.getString(R.string.empty_queue_repeat_one) }

    private fun showUpNext() {
        val current = song("song-2", "Song 2")
        composeRule.setContent {
            val upcoming by engine.upcomingQueue.collectAsState(emptyList<QueueSlot>())
            val repeat by engine.repeatMode.collectAsState(RepeatMode.Off)
            QueueContentTestHost(current, upcoming, repeat)
        }
        composeRule.waitForIdle()
    }

    private fun setRepeat(mode: RepeatMode) {
        onMain { engine.setRepeatMode(mode) }
        await("repeat $mode") { onMain { engine._repeatMode.value == mode } }
        settle()
        composeRule.waitForIdle()
    }

    private fun assertShows(text: String, vararg absent: String) {
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithText(text).assertIsDisplayed()
        absent.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun atTheLastSlotTheTextFollowsTheEnginesRepeatMode() {
        startQueue(count = 3, startIndex = 2, repeat = RepeatMode.All)
        assertTrue("the last slot has nothing after it", onMain { upcomingNow().isEmpty() })
        showUpNext()
        assertShows(repeatAll, nothingQueued, repeatOne)

        setRepeat(RepeatMode.One)
        assertShows(repeatOne, nothingQueued, repeatAll)

        setRepeat(RepeatMode.Off)
        assertShows(nothingQueued, repeatAll, repeatOne)

        setRepeat(RepeatMode.All)
        assertShows(repeatAll, nothingQueued, repeatOne)
        assertEquals("Up Next itself never gains the wrapped songs", 0, onMain { upcomingNow().size })
    }

    private fun upcomingNow(): List<QueueSlot> = engine._upcomingQueue.value
}
