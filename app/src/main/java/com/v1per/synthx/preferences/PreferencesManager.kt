package com.v1per.synthx.preferences

import android.content.Context
import android.net.Uri

class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var musicFolderUri: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_MUSIC_FOLDER_URI, value).apply()

    /** Separate tree from [musicFolderUri] — held with write access, unlike the music folder. */
    var backupFolderUri: String?
        get() = prefs.getString(KEY_BACKUP_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_BACKUP_FOLDER_URI, value).apply()

    /**
     * Second read-only tree, scanned in full on every launch — where the  companion
     * app drops downloads. Separate from [musicFolderUri]: songs found here become ordinary
     * library songs, but the tree is never used to reconcile `is_missing`.
     */
    var newArrivalsFolderUri: String?
        get() = prefs.getString(KEY_NEW_ARRIVALS_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_NEW_ARRIVALS_FOLDER_URI, value).apply()

    /**
     * Every tree that can hold library audio, in no particular order — currently
     * [musicFolderUri] and [newArrivalsFolderUri], skipping whichever aren't set.
     *
     * [backupFolderUri] is deliberately excluded and must stay excluded: it's a write-access
     * tree holding database snapshots, never scanned for audio. Including it would make the
     * scanner walk `.db` files and, worse, imply that songs could legitimately live there.
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

    // --------------------------------------------------------------- destructive-action counter (P3.3)

    /** Delete-song/delete-playlist mutations since the last successful backup of any tier. */
    var destructiveActionsSinceBackup: Int
        get() = prefs.getInt(KEY_DESTRUCTIVE_ACTIONS_SINCE_BACKUP, 0)
        set(value) = prefs.edit().putInt(KEY_DESTRUCTIVE_ACTIONS_SINCE_BACKUP, value).apply()

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
     */
    var migrationInvariantFailurePending: Boolean
        get() = prefs.getBoolean(KEY_MIGRATION_INVARIANT_FAILURE_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_MIGRATION_INVARIANT_FAILURE_PENDING, value).apply()

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

    // ------------------------------------------------- issues scan cache (see IssuesScanWorker)
    // Six of the Issues screen's seven categories are already-warm in-memory StateFlows or a
    // live Room Flow, so they cost nothing to read and are never cached. The seventh —
    // missing album art — is a whole-library filesystem sweep, which is why its result is
    // cached here instead of recomputed on every visit to the screen. Persisted rather than
    // held in memory so the screen has something to show on the first visit of a cold process.

    /** Epoch millis the last [IssuesScanWorker] run completed. 0 means never scanned. */
    var lastIssuesScanTimestamp: Long
        get() = prefs.getLong(KEY_LAST_ISSUES_SCAN_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ISSUES_SCAN_TIMESTAMP, value).apply()

    /** Total issue rows the last scan found, across all seven categories — status-line summary only. */
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
        private const val KEY_LAST_VERIFICATION_ATTEMPT_FAILURE_MESSAGE =
            "last_verification_attempt_failure_message"
        private const val KEY_DESTRUCTIVE_ACTIONS_SINCE_BACKUP = "destructive_actions_since_backup"
        private const val KEY_MIGRATION_SNAPSHOT_FROM_VERSION = "migration_snapshot_from_version"
        private const val KEY_MIGRATION_SNAPSHOT_FOR_VERSION = "migration_snapshot_for_version"
        private const val KEY_MIGRATION_SNAPSHOT_LAUNCHES = "migration_snapshot_confirmed_launches"
        private const val KEY_MIGRATION_INVARIANT_FAILURE_PENDING = "migration_invariant_failure_pending"
        private const val KEY_ART_SWEEP_PENDING = "art_sweep_pending"
        private const val KEY_LAST_ISSUES_SCAN_TIMESTAMP = "last_issues_scan_timestamp"
        private const val KEY_LAST_ISSUES_SCAN_ISSUE_COUNT = "last_issues_scan_issue_count"
        private const val KEY_MISSING_ART_SONG_IDS = "missing_art_song_ids"
    }
}
