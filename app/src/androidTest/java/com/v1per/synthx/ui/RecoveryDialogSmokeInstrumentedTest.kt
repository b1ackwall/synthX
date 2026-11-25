package com.v1per.synthx.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.DatabaseStartupFailureContent
import com.v1per.synthx.EmergencyBackupChooserDialog
import com.v1per.synthx.MigrationRecoveryContent
import com.v1per.synthx.R
import com.v1per.synthx.RestoreTerminalContent
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupPreviewCounts
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.scanner.RootOrganizationCheckResult
import com.v1per.synthx.ui.components.SynthXButtonRole.Destructive
import com.v1per.synthx.ui.components.SynthXButtonRole.Primary
import com.v1per.synthx.ui.components.SynthXButtonRole.Secondary
import com.v1per.synthx.ui.screens.backupsandrestore.MusicFolderRequiredDialog
import com.v1per.synthx.ui.screens.developersettings.RootOrganizationCheckDialog
import com.v1per.synthx.ui.screens.developersettings.RootOrganizationResultDialog
import com.v1per.synthx.ui.screens.replacesong.ReplaceSongOutcomeDialog
import com.v1per.synthx.ui.screens.restore.ConfirmRestoreDialog
import com.v1per.synthx.ui.screens.restore.RestoreAppStatus
import com.v1per.synthx.ui.screens.restore.RestoreCompleteDialog
import com.v1per.synthx.ui.screens.restore.RestoreFailedDialog
import com.v1per.synthx.ui.screens.restore.RestorePreviewFailedDialog
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.testing.assertDialogButton
import com.v1per.synthx.ui.testing.dialogButton
import com.v1per.synthx.ui.theme.SynthXTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opens every startup, restore, screen and debug dialog in each of its button shapes and checks each button's
 * role. Buttons with a callback are tapped to see it reached; Close on the startup and restart surfaces ends the
 * process and the migration restore and emergency actions touch real data, so those are checked, not tapped.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryDialogSmokeInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val taps = mutableListOf<String>()

    private fun string(id: Int) = context.getString(id)

    private fun record(name: String): () -> Unit = { taps += name }

    private fun show(content: @Composable () -> Unit) {
        composeRule.setContent { SynthXTheme { content() } }
        composeRule.waitForIdle()
    }

    private fun tap(label: String) {
        composeRule.dialogButton(label).performClick()
        composeRule.waitForIdle()
    }

    private fun assertTaps(vararg expected: String) = assertEquals(expected.toList(), taps)

    private fun backup(name: String) = BackupInfo(
        databaseUri = "content://x/$name".toUri(),
        metadataUri = null,
        fileName = name,
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = 1_000L,
        restoreEligible = true,
    )

    @Test fun startupFailureRetriesWithALonePrimary() {
        show { DatabaseStartupFailureContent(error = IllegalStateException("disk full"), onRetry = record("retry")) }
        composeRule.assertDialogButton(string(R.string.action_retry), Primary)
        tap(string(R.string.action_retry))
        assertTaps("retry")
    }

    @Test fun migrationRecoveryWithoutASnapshotClosesWithALonePrimary() {
        show { MigrationRecoveryContent(applicationContext = context.applicationContext, snapshotAvailable = false) }
        composeRule.assertDialogButton(string(R.string.action_close), Primary)
    }

    @Test fun migrationRecoveryRestoresWithALoneDestructiveButton() {
        show { MigrationRecoveryContent(applicationContext = context.applicationContext, snapshotAvailable = true) }
        composeRule.assertDialogButton(string(R.string.migration_recovery_restore_action), Destructive)
    }

    @Test fun theRestartRequiredTerminalClosesWithALonePrimary() {
        show { RestoreTerminalContent(RestoreCoordinator.Terminal.RESTART_REQUIRED) }
        composeRule.assertDialogButton(string(R.string.action_close), Primary)
        composeRule.dialogButton(string(R.string.restore_terminal_choose_backup)).assertDoesNotExist()
    }

    @Test fun theRolledBackTerminalClosesWithALonePrimary() {
        show { RestoreTerminalContent(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED) }
        composeRule.assertDialogButton(string(R.string.action_close), Primary)
        composeRule.dialogButton(string(R.string.restore_terminal_export_action)).assertDoesNotExist()
    }

    @Test fun theEmergencyTerminalStacksChooseBackupExportAndClose() {
        show { RestoreTerminalContent(RestoreCoordinator.Terminal.EMERGENCY) }
        composeRule.assertDialogButton(string(R.string.restore_terminal_choose_backup), Primary)
        composeRule.assertDialogButton(string(R.string.restore_terminal_export_action), Secondary)
        composeRule.assertDialogButton(string(R.string.action_close), Secondary)
    }

    @Test fun theBackupChooserListsRowsThatPickAtOnceWithALoneSecondaryCancel() {
        val backups = listOf(backup("daily-1.db"), backup("manual-2.db"))
        val picked = mutableListOf<String>()
        show {
            EmergencyBackupChooserDialog(
                backups = backups,
                actionRunning = false,
                onPick = { picked += it.fileName },
                onDismiss = record("cancel"),
            )
        }
        val row = composeRule.onNode(hasText("manual-2.db") and hasClickAction())
        row.assertIsEnabled().assertHeightIsAtLeast(48.dp)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        row.performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("manual-2.db"), picked)
        tap(string(R.string.action_cancel))
        assertTaps("cancel")
    }

    @Test fun whileARestoreRunsTheChooserRowsAndCancelIgnoreTaps() {
        val picked = mutableListOf<String>()
        show {
            EmergencyBackupChooserDialog(
                backups = listOf(backup("daily-1.db")),
                actionRunning = true,
                onPick = { picked += it.fileName },
                onDismiss = record("cancel"),
            )
        }
        composeRule.onNode(hasText("daily-1.db") and hasClickAction()).assertIsNotEnabled().performClick()
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary, enabled = false)
        tap(string(R.string.action_cancel))
        assertEquals(emptyList<String>(), picked)
        assertTaps()
    }

    @Test fun theInShellTerminalSurfaceClosesWithALonePrimaryEvenInAnEmergency() {
        show { RestoreTerminalSurface(RestoreCoordinator.Terminal.EMERGENCY, diagnosticOperationId = null) }
        composeRule.assertDialogButton(string(R.string.action_close), Primary)
        composeRule.dialogButton(string(R.string.restore_terminal_choose_backup)).assertDoesNotExist()
    }

    @Test fun theInShellTerminalSurfaceClosesWithALonePrimaryWhenARestartIsRequired() {
        show { RestoreTerminalSurface(RestoreCoordinator.Terminal.RESTART_REQUIRED, diagnosticOperationId = "op") }
        composeRule.assertDialogButton(string(R.string.action_close), Primary)
    }

    @Test fun theObservedRestoreOutcomeAcknowledgesASuccessWithALonePrimary() {
        show { RestoreObservedStatusHost(RestoreAppStatus.Succeeded(UUID.randomUUID()), false, viewModel = null) }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        tap(string(R.string.action_ok))
    }

    @Test fun theObservedRestoreOutcomeAcknowledgesAFailureWithALonePrimary() {
        show {
            RestoreObservedStatusHost(RestoreAppStatus.Failed(UUID.randomUUID(), "disk full"), false, viewModel = null)
        }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        tap(string(R.string.action_ok))
    }

    @Test fun musicFolderRequiredSelectsAFolderAsPrimaryWithASecondaryCancel() {
        show { MusicFolderRequiredDialog(onSelectFolder = record("select"), onDismiss = record("cancel")) }
        composeRule.assertDialogButton(string(R.string.restore_music_folder_required_action), Primary)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        tap(string(R.string.restore_music_folder_required_action))
        tap(string(R.string.action_cancel))
        assertTaps("select", "cancel")
    }

    @Test fun confirmRestoreIsGatedOnTheTypedWordAndIsDestructive() {
        show {
            ConfirmRestoreDialog(
                backup = backup("manual-2.db"),
                counts = BackupPreviewCounts(playlistCount = 2, songCount = 10, playlistEntryCount = 12),
                onConfirm = record("restore"),
                onDismiss = record("cancel"),
            )
        }
        val restore = string(R.string.restore_confirm_action)
        composeRule.assertDialogButton(restore, Destructive, enabled = false)
        composeRule.assertDialogButton(string(R.string.action_cancel), Secondary)
        composeRule.onNode(hasSetTextAction()).performTextInput(restore)
        composeRule.waitForIdle()
        composeRule.assertDialogButton(restore, Destructive)
        tap(restore)
        tap(string(R.string.action_cancel))
        assertTaps("restore", "cancel")
    }

    @Test fun theRestorePreviewFailureAcknowledgesWithALonePrimary() {
        show { RestorePreviewFailedDialog(message = "unreadable", onDismiss = record("ok")) }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        tap(string(R.string.action_ok))
        assertTaps("ok")
    }

    @Test fun aCompletedRestoreAcknowledgesWithALonePrimary() {
        show { RestoreCompleteDialog(onOk = record("ok")) }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        tap(string(R.string.action_ok))
        assertTaps("ok")
    }

    @Test fun aFailedRestoreAcknowledgesWithALonePrimary() {
        show { RestoreFailedDialog(message = "disk full", onDismiss = record("ok")) }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        tap(string(R.string.action_ok))
        assertTaps("ok")
    }

    @Test fun theReplaceSongOutcomeAcknowledgesWithALonePrimary() {
        show {
            ReplaceSongOutcomeDialog(
                "done with a warning",
                onAcknowledge = record("ok"),
                onRetry = null,
                retryEnabled = true,
            )
        }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        composeRule.dialogButton(string(R.string.action_retry)).assertDoesNotExist()
        tap(string(R.string.action_ok))
        assertTaps("ok")
    }

    @Test fun theReplaceSongOutcomeOffersASecondaryRetryBesideOk() {
        show {
            ReplaceSongOutcomeDialog(
                "file remained",
                onAcknowledge = record("ok"),
                onRetry = record("retry"),
                retryEnabled = true,
            )
        }
        composeRule.assertDialogButton(string(R.string.action_ok), Primary)
        composeRule.assertDialogButton(string(R.string.action_retry), Secondary)
        tap(string(R.string.action_retry))
        tap(string(R.string.action_ok))
        assertTaps("retry", "ok")
    }

    @Test fun whileTheReplaceSongRetryRunsItIsDisabled() {
        show {
            ReplaceSongOutcomeDialog(
                "file remained",
                onAcknowledge = record("ok"),
                onRetry = record("retry"),
                retryEnabled = false,
            )
        }
        composeRule.assertDialogButton(string(R.string.action_retry), Secondary, enabled = false)
        tap(string(R.string.action_retry))
        assertTaps()
    }

    @Test fun theDebugCheckIsAPrimaryPair() {
        show { RootOrganizationCheckDialog(onCheck = record("check"), onDismiss = record("cancel")) }
        composeRule.assertDialogButton("Check", Primary)
        composeRule.assertDialogButton("Cancel", Secondary)
        tap("Check")
        tap("Cancel")
        assertTaps("check", "cancel")
    }

    @Test fun theDebugResultAcknowledgesWithALonePrimary() {
        show {
            RootOrganizationResultDialog(
                RootOrganizationCheckResult(emptyList(), emptyList()),
                onDismiss = record("ok"),
            )
        }
        composeRule.assertDialogButton("OK", Primary)
        tap("OK")
        assertTaps("ok")
    }
}
