package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.ui.testing.ShellTestHost
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Songs list keeps its scroll position across a tab switch and back. Seeds uniquely named
 * rows that sort ahead of any real data and removes them afterwards.
 */
@RunWith(AndroidJUnit4::class)
class SongsScrollRestoreInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val database get() = SynthXDatabase.getInstance(shell.context)
    private val future = System.currentTimeMillis() + 1_000_000_000L
    private val rows = (0 until ROW_COUNT).map { rank ->
        SongEntity(
            id = "scroll-restore-$rank",
            title = rowTitle(rank),
            albumId = null,
            duration = 1,
            filePath = "file:///scroll-restore-$rank.mp3",
            albumArtUri = null,
            year = null,
            dateAdded = future + (ROW_COUNT - rank),
        )
    }

    @Before fun setUp() = shell.setUp()

    @After fun tearDown() {
        try {
            runBlocking { rows.forEach { database.songDao().deleteSongById(it.id) } }
        } finally {
            shell.tearDown()
        }
    }

    private fun rowTitle(rank: Int) = "Scroll Restore Row %02d".format(rank)

    private fun label(id: Int) = shell.context.getString(id)

    @Test fun songsKeepsItsScrollPositionAcrossATabSwitchAndBack() {
        runBlocking { rows.forEach { database.songDao().upsertSong(it) } }
        shell.startShell()
        shell.clickTab(label(R.string.nav_library))
        shell.navigate(Screen.Songs.route)
        shell.awaitText(rowTitle(0))

        composeRule.onNode(hasScrollAction()).performScrollToIndex(SCROLLED_TO)
        shell.awaitText(rowTitle(SCROLLED_TO))
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(rowTitle(0)) == 0 }

        shell.clickTab(label(R.string.nav_home))
        shell.clickTab(label(R.string.nav_library))

        assertEquals(Screen.Songs.route, shell.route())
        shell.awaitText(rowTitle(SCROLLED_TO))
        composeRule.onNodeWithText(rowTitle(SCROLLED_TO)).assertExists()
        assertEquals("list restarted from the top", 0, shell.textCount(rowTitle(0)))
    }

    private companion object {
        const val ROW_COUNT = 60
        const val SCROLLED_TO = 40
    }
}
