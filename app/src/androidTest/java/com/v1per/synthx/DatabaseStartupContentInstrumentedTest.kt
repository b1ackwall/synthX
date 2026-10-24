package com.v1per.synthx

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.backup.MigrationSnapshotStore
import com.v1per.synthx.data.backup.UnsupportedLiveDatabaseVersionException
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.testing.TestTimeouts
import com.v1per.synthx.ui.theme.SynthXTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the string resources of the two database-startup failure surfaces (every unchanged
 * message keeps its exact text), the deliberately
 * changed first-run-safe generic copy, and the missing-snapshot branch that must not offer a
 * restore it cannot perform.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseStartupContentInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    @get:Rule val composeRule = createComposeRule()

    private val base: Context = ApplicationProvider.getApplicationContext()
    private val testId = "startup-content-${UUID.randomUUID()}"
    private val noBackupDir = File(base.cacheDir, testId).apply { mkdirs() }

    /** Isolated prefs and no-backup dir, so the snapshot decision never sees the app's real ones. */
    private val isolated = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$testId-$name", mode)
        override fun getNoBackupFilesDir(): File = noBackupDir
    }

    @After fun tearDown() {
        base.deleteSharedPreferences("$testId-synthx_prefs")
        noBackupDir.deleteRecursively()
    }

    private fun str(id: Int, vararg args: Any) = base.getString(id, *args)

    // ------------------------------------------------------------ string-resource extraction

    /** The earlier hardcoded literals from MainActivity.kt, copied verbatim. */
    @Test fun extractedStringsMatchTheOldHardcodedLiterals() {
        assertEquals("Database startup paused", str(R.string.startup_failure_title))
        assertEquals("Retry", str(R.string.action_retry))
        assertEquals(
            "This database was created by a newer synthX version. Downgrade is disabled, and the " +
                "database remains unopened. Install a compatible synthX version to continue.",
            str(R.string.startup_failure_message_newer_database),
        )
        assertEquals("Database update needs recovery", str(R.string.migration_recovery_title))
        assertEquals(
            "synthX updated its database, but a post-migration safety check failed. " +
                "Normal use is disabled to protect your library.\n\n" +
                "Your library can be restored to its state from just before this update attempt. " +
                "After restoring, close and reopen synthX to try again.",
            str(R.string.migration_recovery_message),
        )
        assertEquals("Last attempt failed: boom", str(R.string.migration_recovery_last_attempt_failed, "boom"))
        assertEquals("Restoring…", str(R.string.migration_recovery_restoring))
        assertEquals("Restore and restart", str(R.string.migration_recovery_restore_action))
    }

    /** The one intentional text change: first-run-safe, with no restore/upgrade/migration framing. */
    @Test fun genericFailureCopyIsTheNewFirstRunSafeTextAndMentionsNoRestoreOrUpgrade() {
        val generic = str(R.string.startup_failure_message_generic)
        assertEquals(
            "synthX couldn’t finish setting up its database. Nothing has been lost. " +
                "Retry, or close and reopen the app.",
            generic,
        )
        listOf("restor", "upgrad", "updat", "migrat", "snapshot").forEach { word ->
            assertFalse("generic copy must not mention '$word'", generic.contains(word, ignoreCase = true))
        }
    }

    // ------------------------------------------------------------ DatabaseStartupFailureContent

    @Test fun genericFailureShowsNewCopyWithDiagnosticDetailAndRetry() {
        var retries = 0
        composeRule.setContent {
            SynthXTheme {
                DatabaseStartupFailureContent(error = IllegalStateException("disk is full"), onRetry = { retries++ })
            }
        }
        composeRule.onNodeWithText(str(R.string.startup_failure_title)).assertExists()
        composeRule.onNodeWithText(
            str(R.string.startup_failure_message_generic) + "\n\ndisk is full",
        ).assertExists()
        composeRule.onNodeWithText(str(R.string.action_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test fun newerDatabaseFailureKeepsItsMessage() {
        composeRule.setContent {
            SynthXTheme {
                DatabaseStartupFailureContent(
                    error = UnsupportedLiveDatabaseVersionException(actualVersion = 99, currentVersion = 16),
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.startup_failure_message_newer_database)).assertExists()
    }

    // ------------------------------------------------------------ MigrationRecoveryContent

    @Test fun snapshotPresentOffersRestoreAndRestart() {
        composeRule.setContent {
            SynthXTheme { MigrationRecoveryContent(applicationContext = isolated, snapshotAvailable = true) }
        }
        composeRule.onNodeWithText(str(R.string.migration_recovery_title)).assertExists()
        composeRule.onNodeWithText(str(R.string.migration_recovery_message)).assertExists()
        composeRule.onNodeWithText(str(R.string.migration_recovery_restore_action)).assertExists()
    }

    @Test fun snapshotMissingShowsUnavailableMessageAndNoRestoreButton() {
        composeRule.setContent {
            SynthXTheme { MigrationRecoveryContent(applicationContext = isolated, snapshotAvailable = false) }
        }
        composeRule.onNodeWithText(str(R.string.migration_recovery_unavailable_title)).assertExists()
        composeRule.onNodeWithText(str(R.string.migration_recovery_unavailable_message)).assertExists()
        composeRule.onNodeWithText(str(R.string.action_close)).assertExists()
        // No restore affordance of any kind, and none of the restorable-state copy.
        composeRule.onNodeWithText(str(R.string.migration_recovery_restore_action)).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.migration_recovery_restoring)).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.migration_recovery_message)).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.migration_recovery_title)).assertDoesNotExist()
    }

    @Test fun unavailableMessagePointsToBackupsAndDoesNotPromiseARestore() {
        val message = str(R.string.migration_recovery_unavailable_message)
        assertTrue(message.contains("backup", ignoreCase = true))
        assertFalse(message.contains("Restore and restart"))
    }

    // ------------------------------------------------------------ the snapshot decision itself

    @Test fun noSnapshotMetadataMeansUnavailable() {
        assertFalse(isMigrationSnapshotAvailable(isolated))
    }

    @Test fun metadataWithoutAFileMeansUnavailable() {
        PreferencesManager(isolated).migrationSnapshotForVersion = SynthXDatabase.VERSION
        assertFalse(isMigrationSnapshotAvailable(isolated))
    }

    @Test fun fileWithoutMatchingMetadataMeansUnavailable() {
        MigrationSnapshotStore.snapshotFile(isolated).writeText("x")
        assertFalse(isMigrationSnapshotAvailable(isolated))
    }

    @Test fun snapshotForAnOlderTargetVersionMeansUnavailable() {
        PreferencesManager(isolated).migrationSnapshotForVersion = SynthXDatabase.VERSION - 1
        MigrationSnapshotStore.snapshotFile(isolated).writeText("x")
        assertFalse(isMigrationSnapshotAvailable(isolated))
    }

    @Test fun matchingMetadataAndFileMeansAvailable() {
        PreferencesManager(isolated).migrationSnapshotForVersion = SynthXDatabase.VERSION
        MigrationSnapshotStore.snapshotFile(isolated).writeText("x")
        assertTrue(isMigrationSnapshotAvailable(isolated))
    }
}
