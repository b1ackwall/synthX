package com.v1per.synthx.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.repository.Song
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.Background
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Recently Added grid at phone and tablet widths: column count, tile width, gaps and side margin;
 * taps resolving to the tapped song's index; and the loading skeleton keeping the real rows' height at
 * three font scales. The host can be wider and taller than the window, so every check reads unclipped
 * layout bounds, never screen pixels, and allows the 1 px a weighted row may round each tile by.
 */
@RunWith(AndroidJUnit4::class)
class RecentlyAddedGridInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun at360dpTwoColumnsOf158dpKeepTheOld12dpGapAndMargins() {
        show(360.dp) { Section(songCount = 20) }
        assertGrid(songCount = 20, columns = 2, tile = 158.dp, gap = 12.dp, edge = 0.dp)
    }

    @Test fun at411dpTwoColumnsOf168dpGetA24dpGapAndAbout10dpOfExtraMargin() {
        show(411.43.dp) { Section(songCount = 20) }
        // The host lands on whole pixels, so expect the grid at its measured width.
        val expected = recentTileGrid(host().right - host().left)
        assertEquals("extra margin at the host's width", 9.7f, expected.edge.value, 0.1f)
        assertGrid(songCount = 20, columns = 2, tile = 168.dp, gap = 24.dp, edge = expected.edge)
        val title = composeRule.onNodeWithText(context.getString(R.string.library_recently_added))
            .getUnclippedBoundsInRoot()
        assertNear("the section title stays at the base margin", 16.dp, title.left - host().left)
    }

    @Test fun at840dpFiveColumnsOf152dpKeepAShortLastRowInItsColumns() {
        show(840.dp) { Section(songCount = 17) }
        assertGrid(songCount = 17, columns = 5, tile = 152.dp, gap = 12.dp, edge = 0.dp)
    }

    @Test fun atFiveColumnsATapOnTileKQueuesFromSongIndexK() {
        val tapped = mutableListOf<Int>()
        show(840.dp) { RecentlyAddedSection(songs = songs(20), onSeeAllClick = {}, onSongClick = { tapped += it }) }
        assertEquals("tiles in the first row", 5, (0 until 20).count { tile(it).top == tile(0).top })

        // Real touches on the tiles inside the window: the first two columns of the first two rows.
        val touched = listOf(0, 1, 5, 6)
        touched.forEach { composeRule.onNodeWithText(title(it)).performClick() }
        composeRule.runOnIdle {
            assertEquals(touched, tapped.toList())
            tapped.clear()
        }

        // Every tile's click action, the ones past the window's edge included.
        repeat(20) { composeRule.onNodeWithText(title(it)).performSemanticsAction(SemanticsActions.OnClick) }
        composeRule.runOnIdle { assertEquals((0 until 20).toList(), tapped.toList()) }
    }

    @Test fun theSkeletonKeepsTheRealRowsHeightAtFontScale100() = assertSkeletonMatchesReal(fontScale = 1f)

    @Test fun theSkeletonKeepsTheRealRowsHeightAtFontScale130() = assertSkeletonMatchesReal(fontScale = 1.3f)

    @Test fun theSkeletonKeepsTheRealRowsHeightAtFontScale200() = assertSkeletonMatchesReal(fontScale = 2f)

    /** At 840dp (five columns) the skeleton's three rows sit exactly where 15 real tiles do. */
    private fun assertSkeletonMatchesReal(fontScale: Float) {
        show(840.dp, fontScale) {
            Column {
                RecentlyAddedSectionSkeleton()
                Section(songCount = 15)
            }
        }
        val placeholders = composeRule.onAllNodesWithTag(RecentlyAddedSkeletonTileTag)
        assertEquals("three rows of five skeleton tiles", 15, placeholders.fetchSemanticsNodes().size)
        val grids = composeRule.onAllNodesWithTag(RecentlyAddedGridTag)
        val skeletonGrid = grids[0].getUnclippedBoundsInRoot()
        val realGrid = grids[1].getUnclippedBoundsInRoot()
        assertExact("grid height", realGrid.bottom - realGrid.top, skeletonGrid.bottom - skeletonGrid.top)

        repeat(15) { index ->
            val real = tile(index)
            val placeholder = placeholders[index].getUnclippedBoundsInRoot()
            assertExact("tile $index left", real.left, placeholder.left)
            assertExact("tile $index width", real.right - real.left, placeholder.right - placeholder.left)
            assertExact("tile $index top in its grid", real.top - realGrid.top, placeholder.top - skeletonGrid.top)
            assertExact("tile $index height", real.bottom - real.top, placeholder.bottom - placeholder.top)
        }

        // The art is square at the tile width; the rest is the 4dp spacer and the two text lines.
        val real = tile(0)
        val textBlock = (real.bottom - real.top) - (real.right - real.left)
        assertTrue(
            "the real text block ($textBlock) grew with font scale $fontScale",
            textBlock.value >= 4f + (12f + 11f) * fontScale,
        )
    }

    private fun assertGrid(songCount: Int, columns: Int, tile: Dp, gap: Dp, edge: Dp) {
        val host = host()
        val tiles = List(songCount) { tile(it) }
        assertEquals("tiles in the first row", columns, tiles.count { it.top == tiles[0].top })
        tiles.forEachIndexed { index, bounds ->
            val column = index % columns
            assertNear("tile $index width", tile, bounds.right - bounds.left)
            assertExact("tile $index lines up with column $column", tiles[column].left, bounds.left)
            if (column == 0) {
                assertNear("start margin before tile $index", 16.dp + edge, bounds.left - host.left)
            } else {
                assertNear("gap before tile $index", gap, bounds.left - tiles[index - 1].right)
            }
            if (index >= columns) {
                assertNear("row gap above tile $index", gap, bounds.top - tiles[index - columns].bottom)
            }
        }
        assertNear("end margin after the first row", 16.dp + edge, host.right - tiles[columns - 1].right)
    }

    @Composable
    private fun Section(songCount: Int) {
        RecentlyAddedSection(songs = songs(songCount), onSeeAllClick = {}, onSongClick = {})
    }

    private fun show(width: Dp, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeRule.setContent {
            SynthXTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    Box(
                        Modifier
                            .wrapContentWidth(Alignment.Start, unbounded = true)
                            .wrapContentHeight(Alignment.Top, unbounded = true)
                            .width(width)
                            .background(Background)
                            .testTag(HOST_TAG),
                    ) { content() }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun songs(count: Int): ImmutableList<Song> = List(count) { index ->
        Song(
            id = "recent-$index",
            title = title(index),
            artist = "Artist",
            album = "Album",
            duration = 60_000L,
            filePath = "/music/recent-$index.mp3",
        )
    }.toImmutableList()

    private fun title(index: Int) = "Recent $index"

    private fun tile(index: Int): DpRect = composeRule.onNodeWithText(title(index)).getUnclippedBoundsInRoot()

    private fun host(): DpRect = composeRule.onNodeWithTag(HOST_TAG).getUnclippedBoundsInRoot()

    /** Within the 1 px a weighted row or a rounded margin may move an edge by. */
    private fun assertNear(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, 1f / composeRule.density.density + EPSILON_DP)

    /** The same pixel. */
    private fun assertExact(message: String, expected: Dp, actual: Dp) =
        assertEquals(message, expected.value, actual.value, EPSILON_DP)

    private companion object {
        const val HOST_TAG = "recently-added-host"
        const val EPSILON_DP = 0.01f
    }
}
