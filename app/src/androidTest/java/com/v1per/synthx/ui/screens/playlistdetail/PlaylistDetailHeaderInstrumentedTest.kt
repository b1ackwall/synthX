package com.v1per.synthx.ui.screens.playlistdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Playlist
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

/** The detail header under the hero: the stats line and the Play / Shuffle action row. */
@RunWith(AndroidJUnit4::class)
class PlaylistDetailHeaderInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val playlist = Playlist(
        id = "p1", name = "One", groupId = null, albumArtUri = null, artHue = 200, artStructureSeed = 1234L,
    )

    private val played = mutableListOf<List<Song>>()
    private val shuffled = mutableListOf<List<Song>>()

    private fun show(songs: List<Song>) {
        composeRule.setContent {
            PlaylistDetailContent(
                uiState = UiState.Success(playlistDetailData(playlist, songs)),
                currentlyPlayingSongId = null,
                playlistId = "p1",
                onSongTap = { _, _ -> },
                onPlay = { played += it },
                onShuffle = { shuffled += it },
            )
        }
    }

    @Test
    fun heroThenStatsThenActionsWithPlayableOnlyStats() {
        val songs = listOf(song("a", 180_000L), song("gone", 600_000L, missing = true), song("b", 120_000L))
        show(songs)

        val hero = composeRule.onNodeWithTag(PLAYLIST_DETAIL_COVER_TAG).assertIsDisplayed()
        val stats = composeRule.onNodeWithTag(DETAIL_STATS_LINE_TAG).assertIsDisplayed()
        stats.assertTextEquals("2 songs · 5 min")
        val play = composeRule.onNodeWithTag(DETAIL_PLAY_BUTTON_TAG).assertIsDisplayed()
        assertTrue(
            "hero, stats line and action row must stack in that order",
            hero.getUnclippedBoundsInRoot().bottom <= stats.getUnclippedBoundsInRoot().top &&
                stats.getUnclippedBoundsInRoot().bottom <= play.getUnclippedBoundsInRoot().top,
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
    fun anEmptyPlaylistDisablesBothButtons() {
        show(emptyList())
        assertBothDisabledAndInert()
        composeRule.onNodeWithTag(DETAIL_STATS_LINE_TAG).assertTextEquals("0 songs")
    }

    @Test
    fun anAllMissingPlaylistDisablesBothButtons() {
        show(listOf(song("gone", missing = true)))
        assertBothDisabledAndInert()
    }

    private fun assertBothDisabledAndInert() {
        composeRule.onNodeWithTag(DETAIL_PLAY_BUTTON_TAG).assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(DETAIL_SHUFFLE_BUTTON_TAG).assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(emptyList<List<Song>>(), played)
        assertEquals(emptyList<List<Song>>(), shuffled)
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
