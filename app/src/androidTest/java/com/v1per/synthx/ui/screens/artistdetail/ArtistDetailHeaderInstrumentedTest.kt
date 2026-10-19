package com.v1per.synthx.ui.screens.artistdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DETAIL_PLAY_BUTTON_TAG
import com.v1per.synthx.ui.components.DETAIL_SHUFFLE_BUTTON_TAG
import com.v1per.synthx.ui.components.DETAIL_STATS_LINE_TAG
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The artist detail header: the stats line and the Play / Shuffle action row above the songs. */
@RunWith(AndroidJUnit4::class)
class ArtistDetailHeaderInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val played = mutableListOf<List<Song>>()
    private val shuffled = mutableListOf<List<Song>>()
    private val tapped = mutableListOf<Pair<List<Song>, Int>>()

    private fun show(songs: List<Song>) {
        composeRule.setContent {
            ArtistDetailContent(
                uiState = UiState.Success(artistDetailData(songs)),
                currentlyPlayingSongId = null,
                onSongTap = { list, index -> tapped += list to index },
                onPlay = { played += it },
                onShuffle = { shuffled += it },
            )
        }
    }

    @Test
    fun statsThenActionsThenRowsWithPlayableOnlyStats() {
        val songs = listOf(song("a", 180_000L), song("gone", 600_000L, missing = true), song("b", 120_000L))
        show(songs)

        val stats = composeRule.onNodeWithTag(DETAIL_STATS_LINE_TAG).assertIsDisplayed()
        stats.assertTextEquals("2 songs · 5 min")
        val play = composeRule.onNodeWithTag(DETAIL_PLAY_BUTTON_TAG).assertIsDisplayed()
        val firstRow = composeRule.onNodeWithText("a").assertIsDisplayed()
        assertTrue(
            "stats line, action row and first song row must stack in that order",
            stats.getUnclippedBoundsInRoot().bottom <= play.getUnclippedBoundsInRoot().top &&
                play.getUnclippedBoundsInRoot().bottom <= firstRow.getUnclippedBoundsInRoot().top,
        )
    }

    @Test
    fun bothButtonsHandTheWholeListToTheirCallbacks() {
        val songs = listOf(song("a"), song("gone", missing = true), song("b"))
        show(songs)

        composeRule.onNodeWithTag(DETAIL_PLAY_BUTTON_TAG).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(DETAIL_SHUFFLE_BUTTON_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(songs), played)
        assertEquals(listOf(songs), shuffled)
    }

    @Test
    fun anAllMissingArtistDisablesBothButtonsButStillShowsItsRows() {
        show(listOf(song("gone1", missing = true), song("gone2", missing = true)))

        composeRule.onNodeWithTag(DETAIL_STATS_LINE_TAG).assertTextEquals("0 songs")
        composeRule.onNodeWithTag(DETAIL_PLAY_BUTTON_TAG).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(DETAIL_SHUFFLE_BUTTON_TAG).assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<List<Song>>(), played)
        assertEquals(emptyList<List<Song>>(), shuffled)
        composeRule.onNodeWithText("gone1").assertIsDisplayed()
        composeRule.onNodeWithText("gone2").assertIsDisplayed()
    }

    @Test
    fun aRowTapStillCallsOnSongTapWithTheListAndIndex() {
        val songs = listOf(song("a"), song("b"))
        show(songs)

        composeRule.onNodeWithText("b").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(songs to 1), tapped)
    }

    private fun song(id: String, durationMs: Long = 60_000L, missing: Boolean = false) = Song(
        id = id,
        title = id,
        artist = "Artist",
        album = "Album",
        duration = durationMs,
        filePath = "/music/$id.mp3",
        isMissing = missing,
    )
}
