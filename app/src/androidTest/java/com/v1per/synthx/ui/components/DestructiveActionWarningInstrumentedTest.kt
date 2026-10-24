package com.v1per.synthx.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.repository.DeleteSongPreview
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.ui.screens.backupsandrestore.BackupHealthState
import com.v1per.synthx.ui.screens.backupsandrestore.BackupSection
import com.v1per.synthx.ui.screens.backupsandrestore.ExportHealthState
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The destructive-action warning reads the same, with the right noun number, wherever it shows. */
@RunWith(AndroidJUnit4::class)
class DestructiveActionWarningInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun resetTracker() = DestructiveActionTracker.resetForTest(context)

    @After fun clearTracker() = DestructiveActionTracker.resetForTest(context)

    private fun trackerCountOf(count: Int) {
        DestructiveActionTracker.resetForTest(context)
        repeat(count) { DestructiveActionTracker.recordDestructiveAction(context) }
    }

    private fun showDeleteSongDisclosure(count: Int) {
        composeRule.setContent {
            SynthXTheme {
                Column {
                    DeleteSongDisclosure(
                        previewState = DeleteSongPreviewState.Ready(DeleteSongPreview(emptyList())),
                        destructiveActionCount = count,
                        onRetryPreview = {},
                    )
                }
            }
        }
    }

    private fun showBackupSection(count: Int) {
        composeRule.setContent {
            SynthXTheme {
                Column {
                    BackupSection(
                        backupFolderPath = null,
                        backupFolderError = null,
                        backupStatus = null,
                        isBackingUp = false,
                        backupHealth = BackupHealthState.Unknown,
                        exportHealth = ExportHealthState.Unknown,
                        verificationFailure = null,
                        destructiveActionCount = count,
                        onPickBackupFolder = {},
                        onBackupNow = {},
                        onRestoreClick = {},
                    )
                }
            }
        }
    }

    @Test
    fun deleteSongDisclosureReadsSingularForOneAction() {
        showDeleteSongDisclosure(1)
        composeRule.onNodeWithText("1 destructive action since your last successful backup").assertExists()
    }

    @Test
    fun deleteSongDisclosureReadsPluralForManyActions() {
        showDeleteSongDisclosure(4)
        composeRule.onNodeWithText("4 destructive actions since your last successful backup").assertExists()
    }

    @Test
    fun backupSectionReadsSingularForOneAction() {
        showBackupSection(1)
        composeRule.onNodeWithText("1 destructive action since your last successful backup").assertExists()
    }

    @Test
    fun backupSectionReadsPluralForManyActions() {
        showBackupSection(2)
        composeRule.onNodeWithText("2 destructive actions since your last successful backup").assertExists()
    }

    @Test
    fun deletePlaylistDialogReadsSingularForOneAction() {
        trackerCountOf(1)
        composeRule.setContent { SynthXTheme { DeletePlaylistDialog("Mix", onConfirm = {}, onDismiss = {}) } }
        composeRule.onNodeWithText("1 destructive action since your last successful backup").assertExists()
    }

    @Test
    fun deletePlaylistDialogReadsPluralForManyActions() {
        trackerCountOf(3)
        composeRule.setContent { SynthXTheme { DeletePlaylistDialog("Mix", onConfirm = {}, onDismiss = {}) } }
        composeRule.onNodeWithText("3 destructive actions since your last successful backup").assertExists()
    }
}
