package com.v1per.synthx.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real stats_song_count resource, and the stats line built from it. */
@RunWith(AndroidJUnit4::class)
class SongCountPluralInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun songCount(count: Int) = resources.getQuantityString(R.plurals.stats_song_count, count, count)

    @Test
    fun songCountReadsPluralForZero() = assertEquals("0 songs", songCount(0))

    @Test
    fun songCountReadsSingularForOne() = assertEquals("1 song", songCount(1))

    @Test
    fun songCountReadsPluralForTwo() = assertEquals("2 songs", songCount(2))

    @Test
    fun songCountReadsPluralForManySongs() = assertEquals("1000 songs", songCount(1000))

    @Test
    fun statsLineReadsSingularForOneSong() {
        composeRule.setContent { SynthXTheme { DetailStatsLine(songCount = 1, totalDurationMs = 3 * 60_000L) } }
        composeRule.onNodeWithTag(DETAIL_STATS_LINE_TAG).assertTextEquals("1 song · 3 min")
    }
}
