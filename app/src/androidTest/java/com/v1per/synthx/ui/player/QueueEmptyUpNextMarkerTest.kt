package com.v1per.synthx.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.QueueSlot
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.playback.RepeatMode
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Up Next with nothing queued after the current song: the text follows the repeat mode (Repeat Off
 * "Nothing queued after this", Repeat All the continues-from-the-start marker, Repeat One the
 * repeating marker), changes as soon as the mode does while the list stays the same, is read as
 * plain text without a live region (as the old empty text is), and never shows over a list.
 */
@RunWith(AndroidJUnit4::class)
class QueueEmptyUpNextMarkerTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val nothingQueued = resources.getString(R.string.empty_queue_no_upcoming)
    private val repeatAll = resources.getString(R.string.empty_queue_repeat_all)
    private val repeatOne = resources.getString(R.string.empty_queue_repeat_one)

    private val repeatMode = mutableStateOf(RepeatMode.Off)
    private val upcoming = mutableStateOf<List<QueueSlot>>(emptyList())

    private fun song(id: String) = Song(id, "Title $id", "Artist", "Album", 1000L, "audio/$id")

    private fun setUp(mode: RepeatMode) {
        repeatMode.value = mode
        composeRule.setContent {
            QueueContentTestHost(song("current"), upcoming.value, repeatMode.value)
        }
        composeRule.waitForIdle()
    }

    private fun assertShows(text: String, vararg absent: String) {
        composeRule.onNodeWithText(text).assertIsDisplayed()
        composeRule.onNode(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion) and hasText(text))
            .assertExists()
        absent.forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun repeatOffShowsNothingQueuedAfterThis() {
        setUp(RepeatMode.Off)
        assertShows(nothingQueued, repeatAll, repeatOne)
    }

    @Test fun repeatAllShowsThatPlaybackContinuesFromTheStart() {
        setUp(RepeatMode.All)
        assertShows(repeatAll, nothingQueued, repeatOne)
    }

    @Test fun repeatOneShowsThatThisSongRepeats() {
        setUp(RepeatMode.One)
        assertShows(repeatOne, nothingQueued, repeatAll)
    }

    @Test fun theTextFollowsARepeatChangeWhileTheListStaysEmpty() {
        setUp(RepeatMode.Off)
        assertShows(nothingQueued, repeatAll, repeatOne)

        repeatMode.value = RepeatMode.All
        composeRule.waitForIdle()
        assertShows(repeatAll, nothingQueued, repeatOne)

        repeatMode.value = RepeatMode.One
        composeRule.waitForIdle()
        assertShows(repeatOne, nothingQueued, repeatAll)

        repeatMode.value = RepeatMode.Off
        composeRule.waitForIdle()
        assertShows(nothingQueued, repeatAll, repeatOne)
    }

    @Test fun aQueuedSongShowsNoMarker() {
        upcoming.value = listOf(QueueSlot("s1", song("next")))
        setUp(RepeatMode.All)

        composeRule.onNodeWithText("Title next").assertIsDisplayed()
        listOf(nothingQueued, repeatAll, repeatOne).forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }
}
