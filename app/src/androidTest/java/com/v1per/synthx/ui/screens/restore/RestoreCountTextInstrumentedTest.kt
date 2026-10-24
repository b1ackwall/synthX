package com.v1per.synthx.ui.screens.restore

import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupListResult
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The restore confirmation and the backup row render each count with its own noun number. */
@RunWith(AndroidJUnit4::class)
class RestoreCountTextInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val uri: Uri = "content://x/counted.db".toUri()

    private val backup = BackupInfo(
        databaseUri = uri,
        metadataUri = null,
        fileName = "counted.db",
        tier = BackupTier.Manual,
        timestamp = 1_000L,
        reason = null,
        sizeBytes = 1_000L,
        restoreEligible = true,
    )

    private fun showConfirm(playlists: Int, songs: Int, entries: Int) {
        val counts = BackupPreviewCounts(playlistCount = playlists, songCount = songs, playlistEntryCount = entries)
        composeRule.setContent {
            SynthXTheme { ConfirmRestoreDialog(backup = backup, counts = counts, onConfirm = {}, onDismiss = {}) }
        }
    }

    private fun showRow(songs: Int, playlists: Int) {
        val counts = BackupPreviewCounts(playlistCount = playlists, songCount = songs, playlistEntryCount = 0)
        composeRule.setContent {
            SynthXTheme {
                RestoreContent(
                    uiState = UiState.Success(BackupListResult.Success(listOf(backup))),
                    previewCountsCache = mapOf(uri to counts),
                    onSelect = {},
                )
            }
        }
    }

    @Test
    fun confirmationReadsSingularForEveryCountOfOne() {
        showConfirm(playlists = 1, songs = 1, entries = 1)
        composeRule.onNodeWithText("This backup contains 1 playlist, 1 song, and 1 playlist entry.").assertExists()
    }

    @Test
    fun confirmationReadsPluralForEveryManyCount() {
        showConfirm(playlists = 3, songs = 42, entries = 99)
        composeRule.onNodeWithText(
            "This backup contains 3 playlists, 42 songs, and 99 playlist entries.",
        ).assertExists()
    }

    @Test
    fun confirmationNumbersEachNounByItsOwnCount() {
        showConfirm(playlists = 1, songs = 0, entries = 5)
        composeRule.onNodeWithText("This backup contains 1 playlist, 0 songs, and 5 playlist entries.").assertExists()
    }

    @Test
    fun confirmationNumbersEachNounByItsOwnCountWhenOnlyEntriesIsOne() {
        showConfirm(playlists = 2, songs = 7, entries = 1)
        composeRule.onNodeWithText("This backup contains 2 playlists, 7 songs, and 1 playlist entry.").assertExists()
    }

    @Test
    fun rowReadsSingularForOneSongAndOnePlaylist() {
        showRow(songs = 1, playlists = 1)
        composeRule.onNodeWithText("1 song · 1 playlist").assertExists()
    }

    @Test
    fun rowReadsPluralForManySongsAndPlaylists() {
        showRow(songs = 42, playlists = 3)
        composeRule.onNodeWithText("42 songs · 3 playlists").assertExists()
    }

    @Test
    fun rowNumbersEachNounByItsOwnCount() {
        showRow(songs = 1, playlists = 0)
        composeRule.onNodeWithText("1 song · 0 playlists").assertExists()
    }
}
