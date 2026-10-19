package com.v1per.synthx.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.db.PlaylistEntity
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
 * The remove-from-playlist dialog as the real
 * SongActionsHost shows it from a Playlist detail screen in the real shell, removing through the
 * real ViewModel and repository. Seeds one song in one playlist in the live debug database.
 */
@RunWith(AndroidJUnit4::class)
class RemoveFromPlaylistShellInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shell = ShellTestHost(composeRule)
    private val database get() = SynthXDatabase.getInstance(shell.context)
    private val song = SongEntity(
        id = "removeshell-song",
        title = "0000 RemoveShell Song",
        albumId = null,
        duration = 1,
        filePath = "file:///removeshell-song.mp3",
        albumArtUri = null,
        year = null,
        dateAdded = 1L,
    )
    private val playlist = PlaylistEntity(
        id = "removeshell-playlist",
        name = "0000 RemoveShell Playlist",
        description = null,
        groupId = null,
        position = 0,
        createdAt = 1L,
        updatedAt = 1L,
        coverArtUri = null,
    )

    @Before fun setUp() {
        shell.setUp()
        runBlocking {
            database.songDao().upsertSong(song)
            database.playlistDao().insertPlaylist(playlist)
            database.playlistDao().addSongToPlaylist(playlist.id, song.id, addedAt = 1L)
        }
    }

    @After fun tearDown() {
        try {
            runBlocking {
                database.playlistDao().deletePlaylist(playlist.id)
                database.songDao().deleteSongById(song.id)
            }
        } finally {
            shell.tearDown()
        }
    }

    private fun text(id: Int) = shell.context.getString(id)

    private fun inPlaylist(): Boolean = runBlocking {
        song.id in database.playlistDao().getSongIdsForPlaylist(playlist.id)
    }

    private fun openRemoveDialog() {
        shell.startShell()
        shell.navigate(Screen.PlaylistDetail.createRoute(playlist.id, playlist.name))
        shell.awaitText(song.title)
        composeRule.onNode(
            hasContentDescription(text(R.string.cd_more_options)) and hasAnyAncestor(hasText(song.title)),
        ).performClick()
        shell.awaitText(text(R.string.actions_remove_from_playlist))
        composeRule.onNodeWithText(text(R.string.actions_remove_from_playlist)).performClick()
        shell.awaitText(text(R.string.remove_from_playlist_dialog_title))
    }

    private fun awaitDialogClosed() = composeRule.waitUntil(ShellTestHost.WAIT_MS) {
        shell.textCount(text(R.string.remove_from_playlist_dialog_title)) == 0
    }

    @Test fun theDialogNamesTheSongAndThePlaylist() {
        openRemoveDialog()

        assertEquals(
            1,
            shell.textCount(text(R.string.remove_from_playlist_dialog_message).format(song.title, playlist.name)),
        )
    }

    @Test fun cancelClosesTheDialogAndKeepsTheSong() {
        openRemoveDialog()

        composeRule.onNodeWithText(text(R.string.action_cancel)).performClick()
        awaitDialogClosed()

        assertEquals("cancel must not remove", true, inPlaylist())
        assertEquals(1, shell.textCount(song.title))
    }

    @Test fun confirmRemovesTheSongFromThePlaylistAndClosesTheDialog() {
        openRemoveDialog()

        composeRule.onNodeWithText(text(R.string.remove_from_playlist_dialog_confirm)).performClick()
        awaitDialogClosed()

        composeRule.waitUntil(ShellTestHost.WAIT_MS) { !inPlaylist() }
        composeRule.waitUntil(ShellTestHost.WAIT_MS) { shell.textCount(song.title) == 0 }
        assertEquals(
            "the song stays in the library",
            1,
            runBlocking {
                database.songDao().getSongsByIds(listOf(song.id)).size
            },
        )
    }
}
