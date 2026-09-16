package com.v1per.synthx.ui.screens.restore

import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupIneligibilityReason
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupListResult
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.ui.common.UiState
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers R7-F04/F07's UI requirements: each distinct [BackupListResult] renders a distinct,
 * in-wizard-appropriate message (none of them naming "Backups and Restore", the settings screen
 * a first-run user in the recovery wizard cannot reach), and an all-ineligible list surfaces a
 * clear message plus a working "Go back" affordance rather than relying only on the system back
 * gesture.
 */
@RunWith(AndroidJUnit4::class)
class RestoreContentInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun string(id: Int) = context.getString(id)

    @Test
    fun noFolderConfiguredShowsAnInWizardRemedyNotTheUnreachableSettingsScreen() {
        setContent(BackupListResult.NoFolderConfigured)
        composeRule.onNodeWithText(string(R.string.restore_no_folder_configured)).assertExists()
        assertDoesNotMentionBackupsAndRestoreSettingsScreen(string(R.string.restore_no_folder_configured))
    }

    @Test
    fun folderUnreachableShowsADistinctMessageFromNoFolderConfigured() {
        setContent(BackupListResult.FolderUnreachable)
        composeRule.onNodeWithText(string(R.string.restore_folder_unreachable)).assertExists()
        assertDoesNotMentionBackupsAndRestoreSettingsScreen(string(R.string.restore_folder_unreachable))
    }

    @Test
    fun genuinelyEmptyFolderShowsADistinctMessageFromTheOtherTwoEmptyStates() {
        setContent(BackupListResult.Empty)
        composeRule.onNodeWithText(string(R.string.restore_empty)).assertExists()
        assertDoesNotMentionBackupsAndRestoreSettingsScreen(string(R.string.restore_empty))
    }

    @Test
    fun theThreeEmptyStateMessagesAreAllDifferentText() {
        val noFolder = string(R.string.restore_no_folder_configured)
        val unreachable = string(R.string.restore_folder_unreachable)
        val empty = string(R.string.restore_empty)
        assert(setOf(noFolder, unreachable, empty).size == 3) {
            "Expected three distinct empty-state messages, got overlaps: $noFolder / $unreachable / $empty"
        }
    }

    @Test
    fun allIneligibleListShowsAClearMessageAndAWorkingGoBackButton() {
        var backClicked = false
        val backups = listOf(
            backup(fileName = "a.db", eligible = false, reason = BackupIneligibilityReason.MissingMetadata),
            backup(fileName = "b.db", eligible = false, reason = BackupIneligibilityReason.DuplicateDisplayName),
        )
        composeRule.setContent {
            SynthXTheme {
                RestoreContent(
                    uiState = UiState.Success(BackupListResult.Success(backups)),
                    onSelect = {},
                    onBack = { backClicked = true },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.restore_all_ineligible)).assertExists()
        composeRule.onNodeWithText(string(R.string.action_go_back)).assertExists().performClick()
        composeRule.runOnIdle { assert(backClicked) { "Go back button did not invoke onBack" } }
    }

    @Test
    fun aMixedListWithAtLeastOneEligibleBackupDoesNotShowTheAllIneligibleBanner() {
        val backups = listOf(
            backup(fileName = "eligible.db", eligible = true, reason = null),
            backup(fileName = "ineligible.db", eligible = false, reason = BackupIneligibilityReason.SizeMismatch(1, 2)),
        )
        composeRule.setContent {
            SynthXTheme {
                RestoreContent(
                    uiState = UiState.Success(BackupListResult.Success(backups)),
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.restore_all_ineligible)).assertDoesNotExist()
    }

    @Test
    fun eachIneligibilityReasonRendersItsOwnDistinctWording() {
        val backups = listOf(
            backup(fileName = "missing.db", eligible = false, reason = BackupIneligibilityReason.MissingMetadata),
            backup(fileName = "size.db", eligible = false, reason = BackupIneligibilityReason.SizeMismatch(1, 2)),
            backup(fileName = "dup.db", eligible = false, reason = BackupIneligibilityReason.DuplicateDisplayName),
            backup(fileName = "schema.db", eligible = false, reason = BackupIneligibilityReason.UnsupportedSchema(9999)),
        )
        composeRule.setContent {
            SynthXTheme {
                RestoreContent(
                    uiState = UiState.Success(BackupListResult.Success(backups)),
                    onSelect = {},
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.restore_ineligible_missing_metadata)).assertExists()
        composeRule.onNodeWithText(string(R.string.restore_ineligible_size_mismatch)).assertExists()
        composeRule.onNodeWithText(string(R.string.restore_ineligible_duplicate_name)).assertExists()
        composeRule.onNodeWithText(string(R.string.restore_ineligible_unsupported_schema)).assertExists()
    }

    private fun setContent(result: BackupListResult) {
        composeRule.setContent {
            SynthXTheme {
                RestoreContent(uiState = UiState.Success(result), onSelect = {})
            }
        }
    }

    private fun assertDoesNotMentionBackupsAndRestoreSettingsScreen(message: String) {
        val settingsScreenName = string(R.string.screen_backups_and_restore)
        assert(!message.contains(settingsScreenName, ignoreCase = true)) {
            "In-wizard message unexpectedly references the unreachable settings screen: $message"
        }
    }

    private fun backup(
        fileName: String,
        eligible: Boolean,
        reason: BackupIneligibilityReason?,
    ) = BackupInfo(
        databaseUri = Uri.parse("content://x/$fileName"),
        metadataUri = null,
        fileName = fileName,
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = 1_000L,
        restoreEligible = eligible,
        restoreIneligibleReason = reason,
    )
}
