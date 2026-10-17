package com.v1per.synthx.preferences

import android.content.Context
import com.v1per.synthx.ui.testing.TestTimeouts
import java.lang.reflect.Modifier
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins every saved-setting key name and default in [PreferencesManager]. The key strings are the
 * names inside the on-disk "synthx_prefs" file, so a rename silently loses the user's saved value
 * on upgrade. Each key is checked three ways with the literal string: its default when nothing
 * is saved, a value saved under it is read back, and its writer saves under it.
 */
class PreferencesKeysTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    /** One saved setting: literal key, default, a non-default sample, how the app reads and writes it. */
    private class KeyPin<T>(
        val key: String,
        val default: T,
        val sample: T,
        val read: (PreferencesManager) -> T,
        val write: ((PreferencesManager, T) -> Unit)?,
    )

    private val pins: List<KeyPin<*>> = listOf(
        KeyPin("notification_permission_asked", false, true, { it.notificationPermissionAsked }, { m, _ -> m.markNotificationPermissionAskedDurably() }),
        KeyPin("music_folder_uri", null, "content://tree/music", { it.musicFolderUri }, { m, v -> m.musicFolderUri = v }),
        KeyPin("backup_folder_uri", null, "content://tree/backup", { it.backupFolderUri }, { m, v -> m.backupFolderUri = v }),
        KeyPin("new_arrivals_folder_uri", null, "content://tree/new", { it.newArrivalsFolderUri }, { m, v -> m.newArrivalsFolderUri = v }),
        KeyPin("last_daily_backup", 0L, 1_700_000_000_001L, { it.lastDailyBackupTimestamp }, { m, v -> m.lastDailyBackupTimestamp = v }),
        KeyPin("last_backup_attempt_timestamp", 0L, 11L, { it.lastBackupAttemptTimestamp }, { m, v -> m.lastBackupAttemptTimestamp = v }),
        KeyPin("last_backup_attempt_tier", null, "DAILY", { it.lastBackupAttemptTier }, { m, v -> m.lastBackupAttemptTier = v }),
        KeyPin("last_backup_attempt_reason", null, "SCHEDULED", { it.lastBackupAttemptReason }, { m, v -> m.lastBackupAttemptReason = v }),
        KeyPin("last_backup_attempt_succeeded", false, true, { it.lastBackupAttemptSucceeded }, { m, v -> m.lastBackupAttemptSucceeded = v }),
        KeyPin("last_backup_attempt_failure_message", null, "disk full", { it.lastBackupAttemptFailureMessage }, { m, v -> m.lastBackupAttemptFailureMessage = v }),
        KeyPin("last_export_attempt_timestamp", 0L, 12L, { it.lastExportAttemptTimestamp }, { m, v -> m.lastExportAttemptTimestamp = v }),
        KeyPin("last_export_attempt_succeeded", false, true, { it.lastExportAttemptSucceeded }, { m, v -> m.lastExportAttemptSucceeded = v }),
        KeyPin("last_export_attempt_failure_message", null, "no folder", { it.lastExportAttemptFailureMessage }, { m, v -> m.lastExportAttemptFailureMessage = v }),
        KeyPin("last_verification_attempt_timestamp", 0L, 13L, { it.lastVerificationAttemptTimestamp }, { m, v -> m.lastVerificationAttemptTimestamp = v }),
        KeyPin("last_verification_attempt_succeeded", false, true, { it.lastVerificationAttemptSucceeded }, { m, v -> m.lastVerificationAttemptSucceeded = v }),
        KeyPin("last_verification_attempt_skipped_no_eligible_backup", false, true, { it.lastVerificationAttemptSkippedNoEligibleBackup }, { m, v -> m.lastVerificationAttemptSkippedNoEligibleBackup = v }),
        KeyPin("last_verification_attempt_failure_message", null, "bad hash", { it.lastVerificationAttemptFailureMessage }, { m, v -> m.lastVerificationAttemptFailureMessage = v }),
        KeyPin("last_retention_attempt_timestamp", 0L, 14L, { it.lastRetentionAttemptTimestamp }, { m, v -> m.lastRetentionAttemptTimestamp = v }),
        KeyPin("last_retention_attempt_tier", null, "WEEKLY", { it.lastRetentionAttemptTier }, { m, v -> m.lastRetentionAttemptTier = v }),
        KeyPin("last_retention_attempt_attempted", 0, 4, { it.lastRetentionAttemptAttempted }, { m, v -> m.lastRetentionAttemptAttempted = v }),
        KeyPin("last_retention_attempt_succeeded", 0, 3, { it.lastRetentionAttemptSucceeded }, { m, v -> m.lastRetentionAttemptSucceeded = v }),
        KeyPin("last_retention_attempt_orphaned_names", null, "a.db", { it.lastRetentionAttemptOrphanedNames }, { m, v -> m.lastRetentionAttemptOrphanedNames = v }),
        KeyPin("last_retention_attempt_failed_names", null, "b.db", { it.lastRetentionAttemptFailedNames }, { m, v -> m.lastRetentionAttemptFailedNames = v }),
        KeyPin("destructive_actions_since_backup", 0, 5, { it.destructiveActionsSinceBackup }, { m, v -> m.destructiveActionsSinceBackup = v }),
        KeyPin("last_reconciled_cleanup_count", 0, 6, { it.lastReconciledCleanupCount }, { m, v -> m.lastReconciledCleanupCount = v }),
        KeyPin("last_reconciled_cleanup_timestamp", 0L, 15L, { it.lastReconciledCleanupTimestamp }, { m, v -> m.lastReconciledCleanupTimestamp = v }),
        KeyPin("migration_snapshot_from_version", -1, 15, { it.migrationSnapshotFromVersion }, { m, v -> m.migrationSnapshotFromVersion = v }),
        KeyPin("migration_snapshot_for_version", -1, 16, { it.migrationSnapshotForVersion }, { m, v -> m.migrationSnapshotForVersion = v }),
        KeyPin("migration_snapshot_confirmed_launches", 0, 2, { it.migrationSnapshotConfirmedLaunches }, { m, v -> m.migrationSnapshotConfirmedLaunches = v }),
        KeyPin("migration_invariant_failure_pending", false, true, { it.migrationInvariantFailurePending }, { m, v -> m.setMigrationInvariantFailurePendingDurably(v) }),
        KeyPin("art_sweep_pending", false, true, { it.artSweepPending }, { m, v -> m.artSweepPending = v }),
        KeyPin("restore_request_id", null, "req-1", { it.restoreRequestId }, { m, v -> m.trackRestoreRequestDurably(v!!, 99L) }),
        KeyPin("restore_request_submitted_at", 0L, 99L, { it.restoreRequestSubmittedAt }, { m, v -> m.trackRestoreRequestDurably("req-1", v) }),
        KeyPin("restore_outcome_acknowledged", false, true, { it.restoreOutcomeAcknowledged }, null),
        KeyPin("onboarding_complete", false, true, { it.onboardingComplete }, { m, v -> m.setOnboardingCompleteDurably(v) }),
        KeyPin("first_scan_request_id", null, "scan-1", { it.firstScanRequestId }, { m, v -> m.trackFirstScanRequestDurably(v!!) }),
        KeyPin("onboarding_step", null, "PICK_FOLDER", { it.onboardingStepName }, { m, v -> m.setOnboardingProgressDurably(v!!, false) }),
        KeyPin("onboarding_returning_from_recovery", false, true, { it.onboardingReturningFromRecovery }, { m, v -> m.setOnboardingProgressDurably("PICK_FOLDER", v) }),
        KeyPin("last_issues_scan_timestamp", 0L, 16L, { it.lastIssuesScanTimestamp }, { m, v -> m.lastIssuesScanTimestamp = v }),
        KeyPin("last_issues_scan_issue_count", 0, 7, { it.lastIssuesScanIssueCount }, { m, v -> m.lastIssuesScanIssueCount = v }),
        KeyPin("missing_art_song_ids", emptySet(), setOf("s1", "s2"), { it.missingArtSongIds }, { m, v -> m.missingArtSongIds = v }),
    )

    @Test
    fun theSettingsFileIsSynthxPrefsInPrivateMode() {
        val context = PreferencesContext()
        PreferencesManager(context as Context)
        assertEquals(listOf<Pair<String?, Int>>("synthx_prefs" to Context.MODE_PRIVATE), context.requested)
    }

    @Test
    fun theTableCoversAll41KeysOnce() {
        assertEquals(41, pins.size)
        assertEquals(41, pins.map { it.key }.toSet().size, "a key is listed twice")
    }

    /** Guard: a key added to PreferencesManager without a row above fails here by name. */
    @Test
    fun everyKeyConstantInPreferencesManagerHasAPin() {
        val declared = PreferencesManager::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("KEY_") && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .toSet()
        assertEquals(pins.map { it.key }.toSet(), declared)
    }

    @Test
    fun theMusicFolderKeyConstantIsTheLiteral() {
        assertEquals("music_folder_uri", PreferencesManager.KEY_MUSIC_FOLDER_URI)
    }

    @Test
    fun everySettingHasItsDefaultWhenNothingIsSaved() {
        val (manager, _) = preferencesManager()
        val wrong = pins.filter { it.read(manager) != it.default }
            .map { "${it.key}: expected ${it.default}, got ${it.read(manager)}" }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun aValueSavedUnderEachLiteralKeyIsReadBack() {
        val wrong = pins.mapNotNull { pin ->
            val (manager, prefs) = preferencesManager()
            prefs.raw[pin.key] = pin.sample
            val got = runCatching { pin.read(manager) }
            if (got.getOrNull() != pin.sample) "${pin.key}: saved ${pin.sample}, read $got" else null
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun eachWriterSavesUnderItsLiteralKey() {
        val wrong = pins.filter { it.write != null }.mapNotNull { pin ->
            val (manager, prefs) = preferencesManager()
            @Suppress("UNCHECKED_CAST")
            (pin as KeyPin<Any?>).write!!(manager, pin.sample)
            if (prefs.raw[pin.key] != pin.sample) "${pin.key}: wrote ${pin.sample}, file has ${prefs.raw}" else null
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun acknowledgingTheTrackedRestoreSavesUnderItsLiteralKey() {
        val (manager, prefs) = preferencesManager()
        assertTrue(manager.trackRestoreRequestDurably("req-1", 5L))
        assertFalse(manager.acknowledgeRestoreOutcomeDurably("other"), "a different request is not acknowledged")
        assertEquals(false, prefs.raw["restore_outcome_acknowledged"])
        assertTrue(manager.acknowledgeRestoreOutcomeDurably("req-1"))
        assertEquals(true, prefs.raw["restore_outcome_acknowledged"])
    }

    @Test
    fun finishingOnboardingAndTheFirstScanClearTheirLiteralKeys() {
        val (manager, prefs) = preferencesManager()
        manager.setOnboardingProgressDurably("SCAN", returningFromRecovery = true)
        manager.trackFirstScanRequestDurably("scan-1")
        manager.completeFirstScanDurably()
        assertEquals(emptyMap(), prefs.raw, "first scan done clears the step, recovery flag and request id")

        manager.setOnboardingProgressDurably("SCAN", returningFromRecovery = true)
        manager.setOnboardingCompleteDurably(true)
        assertEquals(mapOf<String, Any?>("onboarding_complete" to true), prefs.raw)

        manager.trackFirstScanRequestDurably("scan-2")
        manager.abandonFirstScanDurably()
        assertEquals(mapOf<String, Any?>("onboarding_complete" to false), prefs.raw)
    }
}
