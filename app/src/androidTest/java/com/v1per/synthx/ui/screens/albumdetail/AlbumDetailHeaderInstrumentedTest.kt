package com.v1per.synthx.ui.screens.albumdetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.components.DETAIL_PLAY_BUTTON_TAG
import com.v1per.synthx.ui.components.DETAIL_SHUFFLE_BUTTON_TAG
import com.v1per.synthx.ui.components.DETAIL_STATS_LINE_TAG
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The album detail header: the hero, the stats line and the Play / Shuffle row above the songs,
 * and the row's size and placement on a 360dp-wide screen.
 */
@RunWith(AndroidJUnit4::class)
class AlbumDetailHeaderInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val played = mutableListOf<List<Song>>()
    private val shuffled = mutableListOf<List<Song>>()
    private val tapped = mutableListOf<Pair<List<Song>, Int>>()

    private fun show(songs: List<Song>) {
        composeRule.setContent {
            AlbumDetailContent(
                uiState = UiState.Success(albumDetailData(songs)),
                currentlyPlayingSongId = null,
                onSongTap = { list, index -> tapped += list to index },
                onPlay = { played += it },
                onShuffle = { shuffled += it },
            )
        }
    }

    @Test
    fun heroThenStatsThenActionsThenRowsWithPlayableOnlyStats() {
        val songs = listOf(song("a", 180_000L), song("gone", 600_000L, missing = true), song("b", 120_000L))
        show(songs)

        val hero = composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).assertIsDisplayed()
        val stats = composeRule.onNodeWithTag(DETAIL_STATS_LINE_TAG).assertIsDisplayed()
        stats.assertTextEquals("2 songs · 5 min")
        val play = composeRule.onNodeWithTag(DETAIL_PLAY_BUTTON_TAG).assertIsDisplayed()
        val firstRow = composeRule.onNodeWithText("a").assertIsDisplayed()
        assertTrue(
            "hero, stats line, action row and first song row must stack in that order",
            hero.getUnclippedBoundsInRoot().bottom <= stats.getUnclippedBoundsInRoot().top &&
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
    fun anAllMissingAlbumDisablesBothButtonsButStillShowsItsRows() {
        show(listOf(song("gone1", missing = true), song("gone2", missing = true)))

        composeRule.onNodeWithTag(ALBUM_DETAIL_COVER_TAG).assertIsDisplayed()
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

    /** Shows [songs] on a 360dp-wide screen in the app theme, so the labels use the app font, at [fontScale]. */
    private fun showOnSmallPhone(songs: List<Song>, fontScale: Float) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(SMALL_PHONE) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                SynthXTheme {
                    Box(Modifier.fillMaxSize().testTag(SCREEN_TAG)) {
                        AlbumDetailContent(
                            uiState = UiState.Success(albumDetailData(songs)),
                            currentlyPlayingSongId = null,
                            onSongTap = { _, _ -> },
                            onPlay = { played += it },
                            onShuffle = { shuffled += it },
                        )
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): DpRect = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, TOLERANCE_DP)

    @Test
    fun onA360dpScreenPlayAndShuffleAreCompactSideBySideAndCentredBetweenStatsAndRows() {
        showOnSmallPhone(listOf(song("a"), song("b")), fontScale = 1f)

        val screen = bounds(SCREEN_TAG)
        val hero = bounds(ALBUM_DETAIL_COVER_TAG)
        val stats = bounds(DETAIL_STATS_LINE_TAG)
        val play = bounds(DETAIL_PLAY_BUTTON_TAG)
        val shuffle = bounds(DETAIL_SHUFFLE_BUTTON_TAG)
        val firstRow = composeRule.onNodeWithText("a").getUnclippedBoundsInRoot()
        assertNear("screen width", 360.dp, screen.width)
        listOf("Play" to play, "Shuffle" to shuffle).forEach { (name, button) ->
            assertNear("$name width", 112.dp, button.width)
            assertNear("$name height", 48.dp, button.height)
        }
        assertNear("side by side: same top", shuffle.top, play.top)
        assertNear("Shuffle at the start, Play 12dp after it", 12.dp, play.left - shuffle.right)
        assertNear("centred as a pair", shuffle.left - screen.left, screen.right - play.right)
        assertTrue(
            "hero, stats line, buttons and first song row must stack in that order",
            hero.bottom <= stats.top && stats.bottom <= play.top && play.bottom <= firstRow.top,
        )
    }

    @Test
    fun bothLabelsStayOnOneLineAt100PercentFontScale() = assertLabelsOnOneLine(fontScale = 1f)

    @Test
    fun bothLabelsStayOnOneLineAt130PercentFontScale() = assertLabelsOnOneLine(fontScale = 1.3f)

    // A label split onto two lines still fits the 48dp minimum height, so this reads the line count.
    private fun assertLabelsOnOneLine(fontScale: Float) {
        showOnSmallPhone(listOf(song("a")), fontScale)
        listOf(DETAIL_PLAY_BUTTON_TAG, DETAIL_SHUFFLE_BUTTON_TAG).forEach { tag ->
            composeRule.onNodeWithTag(tag).assertWidthIsEqualTo(112.dp)
            assertEquals("$tag label lines at font scale $fontScale", 1, labelLayout(tag).lineCount)
        }
    }

    // Unmerged: the button merges its label, so the label's own node holds the text layout.
    private fun labelLayout(buttonTag: String): TextLayoutResult {
        val label = composeRule.onNodeWithTag(buttonTag, useUnmergedTree = true).onChild().fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        label.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
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

    private companion object {
        const val SCREEN_TAG = "screen"
        const val TOLERANCE_DP = 0.5f
        val SMALL_PHONE = DpSize(360.dp, 640.dp)
    }
}
