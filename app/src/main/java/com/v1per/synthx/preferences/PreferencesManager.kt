package com.v1per.synthx.preferences

import android.content.Context
import android.net.Uri

class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The root library tree. Read almost exclusively — scanning, missing-detection and playback
     * never write here — but held with **write** access for the one exception: graduation
     * ("Remove from Starred") moves a file out of [newArrivalsFolderUri] and into this tree, so
     * it has to be able to create a file under it.
     *
     * A grant taken before that was true persists read-only and does not upgrade itself; the
     * folder has to be re-picked once. `AudioFileWalk.isRootWritable` is the check that catches
     * it, and graduation reports it as something to act on rather than as a create failure.
     */
    var musicFolderUri: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_MUSIC_FOLDER_URI, value).apply()

    /** Separate tree from [musicFolderUri] — holds database snapshots, not audio. Write access,
     *  same as the music folder but for an unrelated reason. */
    var backupFolderUri: String?
        get() = prefs.getString(KEY_BACKUP_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_BACKUP_FOLDER_URI, value).apply()

    /**
     * Second audio tree, scanned in full on every launch — where the  companion app drops
     * downloads. Separate from [musicFolderUri]: songs found here become ordinary library songs,
     * but the tree is never used to reconcile `is_missing`.
     *
     * Held with **write** access, like [musicFolderUri] and for the other half of the same
     * reason: graduation deletes the original from this tree once its copy under the music root
     * is verified, and complete-delete removes a file from whichever tree holds it. Neither is
     * possible against a read-only grant.
     *
     * Same caveat as [musicFolderUri]: a grant taken before that was true persists read-only and
     * does not upgrade itself — the folder has to be re-picked once.
     */
    var newArrivalsFolderUri: String?
        get() = prefs.getString(KEY_NEW_ARRIVALS_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_NEW_ARRIVALS_FOLDER_URI, value).apply()

    /**
     * Every tree that can hold library audio, in no particular order — currently
     * [musicFolderUri] and [newArrivalsFolderUri], skipping whichever aren't set.
     *
     * [backupFolderUri] is deliberately excluded and must stay excluded: it holds database
     * snapshots and is never scanned for audio. Including it would make the scanner walk `.db`
     * files and, worse, imply that songs could legitimately live there. (Write access is no
     * longer what sets it apart — all three trees hold one now — but the exclusion never rested
     * on that.)
     *
     * Exists so missing-detection can reconcile against *all* configured roots rather than
     * only the one being walked — see FolderScanner.scan's `otherRootUris`.
     */
    fun configuredAudioRootUris(): List<Uri> =
        listOfNotNull(musicFolderUri, newArrivalsFolderUri).map(Uri::parse)

    /** Epoch millis of the last successful Daily backup. 0 means none taken yet. */
    var lastDailyBackupTimestamp: Long
        get() = prefs.getLong(KEY_LAST_DAILY_BACKUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_DAILY_BACKUP, value).apply()

    // ----------------------------------------------- backup/export/verification outcomes (see BackupOutcomeStore)
    // Raw primitives only — composing these into BackupOutcomeStore's typed outcomes is that
    // object's job, keeping this class free of a data/backup dependency.

    /** Epoch millis of the last createBackup() attempt of any tier/reason. 0 means none yet. */
    var lastBackupAttemptTimestamp: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_ATTEMPT_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_ATTEMPT_TIMESTAMP, value).apply()

    var lastBackupAttemptTier: String?
        get() = prefs.getString(KEY_LAST_BACKUP_ATTEMPT_TIER, null)
        set(value) = prefs.edit().putString(KEY_LAST_BACKUP_ATTEMPT_TIER, value).apply()

    var lastBackupAttemptReason: String?
        get() = prefs.getString(KEY_LAST_BACKUP_ATTEMPT_REASON, null)
        set(value) = prefs.edit().putString(KEY_LAST_BACKUP_ATTEMPT_REASON, value).apply()

    var lastBackupAttemptSucceeded: Boolean
        get() = prefs.getBoolean(KEY_LAST_BACKUP_ATTEMPT_SUCCEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_BACKUP_ATTEMPT_SUCCEEDED, value).apply()

    var lastBackupAttemptFailureMessage: String?
        get() = prefs.getString(KEY_LAST_BACKUP_ATTEMPT_FAILURE_MESSAGE, null)
        set(value) = prefs.edit().putString(KEY_LAST_BACKUP_ATTEMPT_FAILURE_MESSAGE, value).apply()

    /** Epoch millis of the last playlist-export attempt. 0 means none yet. */
    var lastExportAttemptTimestamp: Long
        get() = prefs.getLong(KEY_LAST_EXPORT_ATTEMPT_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EXPORT_ATTEMPT_TIMESTAMP, value).apply()

    var lastExportAttemptSucceeded: Boolean
        get() = prefs.getBoolean(KEY_LAST_EXPORT_ATTEMPT_SUCCEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_EXPORT_ATTEMPT_SUCCEEDED, value).apply()

    var lastExportAttemptFailureMessage: String?
        get() = prefs.getString(KEY_LAST_EXPORT_ATTEMPT_FAILURE_MESSAGE, null)
        set(value) = prefs.edit().putString(KEY_LAST_EXPORT_ATTEMPT_FAILURE_MESSAGE, value).apply()

    /** Epoch millis of the last periodic re-validation of the most recent backup. 0 = none yet. */
    var lastVerificationAttemptTimestamp: Long
        get() = prefs.getLong(KEY_LAST_VERIFICATION_ATTEMPT_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_VERIFICATION_ATTEMPT_TIMESTAMP, value).apply()

    var lastVerificationAttemptSucceeded: Boolean
        get() = prefs.getBoolean(KEY_LAST_VERIFICATION_ATTEMPT_SUCCEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_VERIFICATION_ATTEMPT_SUCCEEDED, value).apply()

    var lastVerificationAttemptFailureMessage: String?
        get() = prefs.getString(KEY_LAST_VERIFICATION_ATTEMPT_FAILURE_MESSAGE, null)
        set(value) = prefs.edit().putString(KEY_LAST_VERIFICATION_ATTEMPT_FAILURE_MESSAGE, value).apply()

    /** R8-A11/P7-T5: true when the last verification found no restore-eligible backup to check
     *  at all — distinct from an actual pass/fail, see [lastVerificationAttemptSucceeded]. */
    var lastVerificationAttemptSkippedNoEligibleBackup: Boolean
        get() = prefs.getBoolean(KEY_LAST_VERIFICATION_ATTEMPT_SKIPPED_NO_ELIGIBLE_BACKUP, false)
        set(value) = prefs.edit()
            .putBoolean(KEY_LAST_VERIFICATION_ATTEMPT_SKIPPED_NO_ELIGIBLE_BACKUP, value)
            .apply()

    // --------------------------------------------------------------- destructive-action counter (P3.3)

    /** Delete-song/delete-playlist mutations since the last successful backup of any tier. */
    var destructiveActionsSinceBackup: Int
        get() = prefs.getInt(KEY_DESTRUCTIVE_ACTIONS_SINCE_BACKUP, 0)
        set(value) = prefs.edit().putInt(KEY_DESTRUCTIVE_ACTIONS_SINCE_BACKUP, value).apply()

    // -------------------------------------------- pending-cleanup reconciliation (P3-T1)

    /** Count of leftover [com.v1per.synthx.data.db.PendingCleanupEntity] records the most recent
     *  startup reconciliation pass resolved. 0 means none were found. */
    var lastReconciledCleanupCount: Int
        get() = prefs.getInt(KEY_LAST_RECONCILED_CLEANUP_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_RECONCILED_CLEANUP_COUNT, value).apply()

    var lastReconciledCleanupTimestamp: Long
        get() = prefs.getLong(KEY_LAST_RECONCILED_CLEANUP_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RECONCILED_CLEANUP_TIMESTAMP, value).apply()

    // --------------------------------------------------- migration safety (see MigrationSnapshotStore)

    /** The database version the current internal pre-migration snapshot was captured from. -1 = none. */
    var migrationSnapshotFromVersion: Int
        get() = prefs.getInt(KEY_MIGRATION_SNAPSHOT_FROM_VERSION, -1)
        set(value) = prefs.edit().putInt(KEY_MIGRATION_SNAPSHOT_FROM_VERSION, value).apply()

    /** The database version the current internal pre-migration snapshot protects. -1 = none. */
    var migrationSnapshotForVersion: Int
        get() = prefs.getInt(KEY_MIGRATION_SNAPSHOT_FOR_VERSION, -1)
        set(value) = prefs.edit().putInt(KEY_MIGRATION_SNAPSHOT_FOR_VERSION, value).apply()

    /** Successful launches counted at [migrationSnapshotForVersion] since the snapshot was taken. */
    var migrationSnapshotConfirmedLaunches: Int
        get() = prefs.getInt(KEY_MIGRATION_SNAPSHOT_LAUNCHES, 0)
        set(value) = prefs.edit().putInt(KEY_MIGRATION_SNAPSHOT_LAUNCHES, value).apply()

    /**
     * Set the moment a post-migration invariant check fails; cleared only once the internal
     * snapshot has been restored. While true, the startup gate refuses readiness regardless of
     * what the live database's own `user_version` reports (it may already read as "current" —
     * the migration ran, it just failed validation).
     *
     * Read-only here by design (P3-T3 / R1-F08/F09): both writers of this flag
     * ([com.v1per.synthx.data.backup.DatabaseStartupGate] on failure,
     * [com.v1per.synthx.data.backup.MigrationRecoveryAction] on recovery) are safety-critical —
     * a process death between an async `apply()` returning and the write actually landing can
     * silently lose either transition. Both write through
     * [setMigrationInvariantFailurePendingDurably] instead, which blocks on `commit()`.
     */
    val migrationInvariantFailurePending: Boolean
        get() = prefs.getBoolean(KEY_MIGRATION_INVARIANT_FAILURE_PENDING, false)

    /**
     * Synchronous counterpart to [migrationInvariantFailurePending]'s (removed) setter. Blocks
     * until the write is committed to disk and reports whether it actually succeeded, so a
     * caller on this critical path can react to a failed commit instead of assuming success the
     * way `apply()` would.
     */
    fun setMigrationInvariantFailurePendingDurably(value: Boolean): Boolean =
        prefs.edit().putBoolean(KEY_MIGRATION_INVARIANT_FAILURE_PENDING, value).commit()

    // --------------------------------------------------------------------- post-restore art sweep

    /**
     * Set the moment a restore's outcome resolves to installed (see RestoreTransaction's
     * COMPLETE_INSTALLED_AND_NORMAL/ACCEPT_COMPLETE_HISTORY_AND_NORMAL branches), read and
     * cleared once by [MainActivity.runLaunchMaintenance] on the next launch. Album art is
     * deliberately excluded from backups, and a normal rescan can't self-heal a restored song's
     * missing art (identity resolution treats it as "existing, unchanged" — see
     * SongIngestor.retagIfChanged), so this flag is what triggers the one-time automatic
     * recovery sweep (see ArtRecovery) instead. Same persisted-boolean shape as
     * [migrationInvariantFailurePending] — chosen because the restore flow's forced
     * exitProcess(0) means any in-memory signal (e.g. RestoreCoordinator.Terminal) is gone by
     * the time the next process reads it.
     */
    var artSweepPending: Boolean
        get() = prefs.getBoolean(KEY_ART_SWEEP_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ART_SWEEP_PENDING, value).apply()

    // --------------------------------------------------------- durable restore observation

    /** WorkManager UUID for the restore whose outcome still belongs to the user. */
    var restoreRequestId: String?
        get() = prefs.getString(KEY_RESTORE_REQUEST_ID, null)
        private set(value) = prefs.edit().putString(KEY_RESTORE_REQUEST_ID, value).apply()

    /** Wall-clock submission time used only for the queued-work grace period. */
    var restoreRequestSubmittedAt: Long
        get() = prefs.getLong(KEY_RESTORE_REQUEST_SUBMITTED_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_RESTORE_REQUEST_SUBMITTED_AT, value).apply()

    /** True only after a terminal WorkInfo has actually been put on screen. */
    var restoreOutcomeAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_OUTCOME_ACKNOWLEDGED, false)
        private set(value) = prefs.edit().putBoolean(KEY_RESTORE_OUTCOME_ACKNOWLEDGED, value).apply()

    /**
     * Commits the identity before enqueue returns to UI code. KEEP can resolve to an already
     * active request; retain that request's original clock in that case so its wait is not reset
     * by a duplicate tap/recreated caller.
     */
    fun trackRestoreRequestDurably(requestId: String, submittedAt: Long): Boolean {
        val sameRequest = restoreRequestId == requestId
        return prefs.edit()
            .putString(KEY_RESTORE_REQUEST_ID, requestId)
            .putLong(
                KEY_RESTORE_REQUEST_SUBMITTED_AT,
                if (sameRequest && restoreRequestSubmittedAt > 0L) restoreRequestSubmittedAt else submittedAt,
            )
            .putBoolean(KEY_RESTORE_OUTCOME_ACKNOWLEDGED, false)
            .commit()
    }

    /** The observer calls this at composition time, not merely when it discovers terminal work. */
    fun acknowledgeRestoreOutcomeDurably(requestId: String): Boolean {
        if (restoreRequestId != requestId) return false
        return prefs.edit().putBoolean(KEY_RESTORE_OUTCOME_ACKNOWLEDGED, true).commit()
    }

    // --------------------------------------------------------- onboarding/recovery completion (P7-T2)

    /**
     * True only once onboarding has genuinely finished — a fresh-install music-folder pick, or
     * (R7-F02) a recovery restore reaching genuine terminal success. Deliberately NOT derived
     * from [musicFolderUri] != null: recovery's first step sets that long before its restore
     * actually succeeds, which is exactly the bug this flag exists to fix. Read-only here by
     * design, same reasoning as [migrationInvariantFailurePending]: the sole writer is
     * [setOnboardingCompleteDurably], which blocks on `commit()` since this flag gates
     * MainActivity's cold-start destination choice — a lost `apply()` write would silently
     * strand the user in (or bounce them out of) the wizard on the very next launch.
     */
    val onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    /**
     * Synchronous counterpart to [onboardingComplete]'s (removed) setter. Also clears any
     * persisted wizard position in the same commit once [value] is true — that position exists
     * only to resume an in-progress wizard, and becomes stale the instant onboarding is done.
     */
    fun setOnboardingCompleteDurably(value: Boolean): Boolean {
        val editor = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, value)
        if (value) {
            editor.remove(KEY_ONBOARDING_STEP).remove(KEY_ONBOARDING_RETURNING_FROM_RECOVERY)
        }
        return editor.commit()
    }

    /**
     * Name of the wizard's current [com.v1per.synthx.ui.screens.onboarding.OnboardingStep],
     * persisted so process death or a swipe-away mid-recovery resumes at the correct step
     * instead of silently restarting at Choice (R7-F13). Null once onboarding is complete or
     * has never been started. Read-only here, same reasoning as [onboardingComplete] — the sole
     * writer is [setOnboardingProgressDurably].
     */
    val onboardingStepName: String?
        get() = prefs.getString(KEY_ONBOARDING_STEP, null)

    /** Companion to [onboardingStepName] — see OnboardingViewModel.returningFromRecovery. */
    val onboardingReturningFromRecovery: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_RETURNING_FROM_RECOVERY, false)

    /** Commits both fields together so a reader never observes one updated without the other. */
    fun setOnboardingProgressDurably(stepName: String, returningFromRecovery: Boolean): Boolean =
        prefs.edit()
            .putString(KEY_ONBOARDING_STEP, stepName)
            .putBoolean(KEY_ONBOARDING_RETURNING_FROM_RECOVERY, returningFromRecovery)
            .commit()

    // ------------------------------------------------- issues scan cache (see IssuesScanWorker)
    // Six categories stay live. Missing-art and missing-palette aggregate IDs are persisted
    // so the screen and both Fix actions share the last worker snapshot across cold starts.

    /** Epoch millis the last [IssuesScanWorker] run completed. 0 means never scanned. */
    var lastIssuesScanTimestamp: Long
        get() = prefs.getLong(KEY_LAST_ISSUES_SCAN_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ISSUES_SCAN_TIMESTAMP, value).apply()

    /** Total issue rows the last scan found, across all eight categories — status-line summary only. */
    var lastIssuesScanIssueCount: Int
        get() = prefs.getInt(KEY_LAST_ISSUES_SCAN_ISSUE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_ISSUES_SCAN_ISSUE_COUNT, value).apply()

    /**
     * Ids of songs the last scan found missing an art file. A [Set] rather than an ordered list
     * because order carries no meaning here (the Issues screen renders one aggregate row from
     * the whole collection) and `putStringSet` is the SharedPreferences primitive that fits.
     *
     * The getter copies: `getStringSet`'s returned instance must not be mutated or retained.
     */
    var missingArtSongIds: Set<String>
        get() = prefs.getStringSet(KEY_MISSING_ART_SONG_IDS, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_MISSING_ART_SONG_IDS, value).apply()

    /** Fixable songs with artwork but no stored palette; copy the preference-owned set. */
    var missingPaletteSongIds: Set<String>
        get() = prefs.getStringSet(KEY_MISSING_PALETTE_SONG_IDS, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_MISSING_PALETTE_SONG_IDS, value).apply()

    var lastIssuesScanMissingPaletteCount: Int
        get() = prefs.getInt(KEY_LAST_ISSUES_SCAN_MISSING_PALETTE_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_ISSUES_SCAN_MISSING_PALETTE_COUNT, value).apply()

    companion object {
        private const val PREFS_NAME = "synthx_prefs"
        const val KEY_MUSIC_FOLDER_URI = "music_folder_uri"
        private const val KEY_BACKUP_FOLDER_URI = "backup_folder_uri"
        private const val KEY_NEW_ARRIVALS_FOLDER_URI = "new_arrivals_folder_uri"
        private const val KEY_LAST_DAILY_BACKUP = "last_daily_backup"
        private const val KEY_LAST_BACKUP_ATTEMPT_TIMESTAMP = "last_backup_attempt_timestamp"
        private const val KEY_LAST_BACKUP_ATTEMPT_TIER = "last_backup_attempt_tier"
        private const val KEY_LAST_BACKUP_ATTEMPT_REASON = "last_backup_attempt_reason"
        private const val KEY_LAST_BACKUP_ATTEMPT_SUCCEEDED = "last_backup_attempt_succeeded"
        private const val KEY_LAST_BACKUP_ATTEMPT_FAILURE_MESSAGE = "last_backup_attempt_failure_message"
        private const val KEY_LAST_EXPORT_ATTEMPT_TIMESTAMP = "last_export_attempt_timestamp"
        private const val KEY_LAST_EXPORT_ATTEMPT_SUCCEEDED = "last_export_attempt_succeeded"
        private const val KEY_LAST_EXPORT_ATTEMPT_FAILURE_MESSAGE = "last_export_attempt_failure_message"
        private const val KEY_LAST_VERIFICATION_ATTEMPT_TIMESTAMP = "last_verification_attempt_timestamp"
        private const val KEY_LAST_VERIFICATION_ATTEMPT_SUCCEEDED = "last_verification_attempt_succeeded"
        private const val KEY_LAST_VERIFICATION_ATTEMPT_SKIPPED_NO_ELIGIBLE_BACKUP =
            "last_verification_attempt_skipped_no_eligible_backup"
        private const val KEY_LAST_VERIFICATION_ATTEMPT_FAILURE_MESSAGE =
            "last_verification_attempt_failure_message"
        private const val KEY_DESTRUCTIVE_ACTIONS_SINCE_BACKUP = "destructive_actions_since_backup"
        private const val KEY_MIGRATION_SNAPSHOT_FROM_VERSION = "migration_snapshot_from_version"
        private const val KEY_MIGRATION_SNAPSHOT_FOR_VERSION = "migration_snapshot_for_version"
        private const val KEY_MIGRATION_SNAPSHOT_LAUNCHES = "migration_snapshot_confirmed_launches"
        private const val KEY_MIGRATION_INVARIANT_FAILURE_PENDING = "migration_invariant_failure_pending"
        private const val KEY_ART_SWEEP_PENDING = "art_sweep_pending"
        private const val KEY_RESTORE_REQUEST_ID = "restore_request_id"
        private const val KEY_RESTORE_REQUEST_SUBMITTED_AT = "restore_request_submitted_at"
        private const val KEY_RESTORE_OUTCOME_ACKNOWLEDGED = "restore_outcome_acknowledged"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_ONBOARDING_STEP = "onboarding_step"
        private const val KEY_ONBOARDING_RETURNING_FROM_RECOVERY = "onboarding_returning_from_recovery"
        private const val KEY_LAST_ISSUES_SCAN_TIMESTAMP = "last_issues_scan_timestamp"
        private const val KEY_LAST_ISSUES_SCAN_ISSUE_COUNT = "last_issues_scan_issue_count"
        private const val KEY_MISSING_ART_SONG_IDS = "missing_art_song_ids"
        private const val KEY_MISSING_PALETTE_SONG_IDS = "missing_palette_song_ids"
        private const val KEY_LAST_ISSUES_SCAN_MISSING_PALETTE_COUNT = "last_issues_scan_missing_palette_count"
        private const val KEY_LAST_RECONCILED_CLEANUP_COUNT = "last_reconciled_cleanup_count"
        private const val KEY_LAST_RECONCILED_CLEANUP_TIMESTAMP = "last_reconciled_cleanup_timestamp"
    }
}
