package com.v1per.synthx.data.backup

import android.content.Context
import android.util.Log
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackupAttemptOutcome(
    val timestamp: Long,
    val tier: BackupTier,
    val reason: String?,
    val succeeded: Boolean,
    val failureMessage: String?,
)

data class ExportAttemptOutcome(
    val timestamp: Long,
    val succeeded: Boolean,
    val failureMessage: String?,
)

/**
 * [skippedNoEligibleBackup]: true only when [BackupManager.verifyMostRecentBackup]
 * found no [BackupInfo.restoreEligible] entry to check at all (no backup yet, or every listed
 * entry is ineligible). Distinct from [succeeded]`= true` (a real byte-level check ran and
 * passed) and from [succeeded]`= false` with this flag clear (a real check ran and failed) —
 * collapsing "nothing to verify" into either of those would give false assurance.
 * [succeeded] is always `false` alongside this flag; callers that only care whether a
 * real failure occurred should check `!succeeded && !skippedNoEligibleBackup`.
 */
data class VerificationAttemptOutcome(
    val timestamp: Long,
    val succeeded: Boolean,
    val failureMessage: String?,
    val skippedNoEligibleBackup: Boolean = false,
)

/**
 * [BackupRetention.prune]'s own accounting, recorded SEPARATELY from
 * [BackupAttemptOutcome] — a prune that had failures must never cause the publish it followed to
 * report as failed (see the "preserve retention's best-effort policy" comment above
 * [BackupManager]'s retention call site). [attempted]`== 0` means there was nothing to prune;
 * `hadFailures == false` with `attempted > 0` means every prune completed cleanly.
 */
data class RetentionAttemptOutcome(
    val timestamp: Long,
    val tier: BackupTier,
    val attempted: Int,
    val succeeded: Int,
    val orphanedDatabaseNames: List<String>,
    val failedDatabaseNames: List<String>,
) {
    val hadFailures: Boolean get() = orphanedDatabaseNames.isNotEmpty() || failedDatabaseNames.isNotEmpty()
}

/**
 * The other half of [BackupFolderHealth]. That answers "can I write to the backup folder right
 * now" — a pre-condition check. This answers "did the last actual backup or export attempt
 * succeed, and when".
 *
 * [BackupManager.createBackup] is the single choke point every tier/reason (Daily, Rescan,
 * delete-song, delete-playlist, folder-change, manual, migration) already funnels through, so
 * recording there — rather than at each of those call sites — is what makes "every call site
 * records its outcome" true by construction instead of by remembering to do it six times.
 * [PlaylistExportManager.writeExport] is the equivalent single choke point for exports.
 *
 * Persisted in SharedPreferences (see the raw fields on [PreferencesManager]) rather than a Room
 * table: this is small, single-row, informational state with no relationship to the backup/restore
 * schema machinery those tables serve. Mirrored into in-memory StateFlows, initialized once per
 * process, so BackupsAndRestoreViewModel can observe it the same way MainScaffold already
 * observes [BackupFolderLaunchCheck.grantLost] instead of re-reading SharedPreferences per
 * recomposition.
 */
object BackupOutcomeStore {

    private val _lastBackupOutcome = MutableStateFlow<BackupAttemptOutcome?>(null)
    val lastBackupOutcome: StateFlow<BackupAttemptOutcome?> = _lastBackupOutcome.asStateFlow()

    private val _lastExportOutcome = MutableStateFlow<ExportAttemptOutcome?>(null)
    val lastExportOutcome: StateFlow<ExportAttemptOutcome?> = _lastExportOutcome.asStateFlow()

    private val _lastVerificationOutcome = MutableStateFlow<VerificationAttemptOutcome?>(null)
    val lastVerificationOutcome: StateFlow<VerificationAttemptOutcome?> = _lastVerificationOutcome.asStateFlow()

    private val _lastRetentionOutcome = MutableStateFlow<RetentionAttemptOutcome?>(null)
    val lastRetentionOutcome: StateFlow<RetentionAttemptOutcome?> = _lastRetentionOutcome.asStateFlow()

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = PreferencesManager(context.applicationContext)

        if (prefs.lastBackupAttemptTimestamp != 0L) {
            _lastBackupOutcome.value = BackupAttemptOutcome(
                timestamp = prefs.lastBackupAttemptTimestamp,
                tier = runCatching { BackupTier.valueOf(prefs.lastBackupAttemptTier.orEmpty()) }
                    .getOrDefault(BackupTier.Auto),
                reason = prefs.lastBackupAttemptReason,
                succeeded = prefs.lastBackupAttemptSucceeded,
                failureMessage = prefs.lastBackupAttemptFailureMessage,
            )
        }
        if (prefs.lastExportAttemptTimestamp != 0L) {
            _lastExportOutcome.value = ExportAttemptOutcome(
                timestamp = prefs.lastExportAttemptTimestamp,
                succeeded = prefs.lastExportAttemptSucceeded,
                failureMessage = prefs.lastExportAttemptFailureMessage,
            )
        }
        if (prefs.lastVerificationAttemptTimestamp != 0L) {
            _lastVerificationOutcome.value = VerificationAttemptOutcome(
                timestamp = prefs.lastVerificationAttemptTimestamp,
                succeeded = prefs.lastVerificationAttemptSucceeded,
                failureMessage = prefs.lastVerificationAttemptFailureMessage,
                skippedNoEligibleBackup = prefs.lastVerificationAttemptSkippedNoEligibleBackup,
            )
        }
        if (prefs.lastRetentionAttemptTimestamp != 0L) {
            val tier = runCatching { BackupTier.valueOf(prefs.lastRetentionAttemptTier.orEmpty()) }
                .getOrNull()
            if (tier != null) {
                _lastRetentionOutcome.value = RetentionAttemptOutcome(
                    timestamp = prefs.lastRetentionAttemptTimestamp,
                    tier = tier,
                    attempted = prefs.lastRetentionAttemptAttempted,
                    succeeded = prefs.lastRetentionAttemptSucceeded,
                    orphanedDatabaseNames = prefs.lastRetentionAttemptOrphanedNames.toNameList(),
                    failedDatabaseNames = prefs.lastRetentionAttemptFailedNames.toNameList(),
                )
            }
        }
    }

    private fun String?.toNameList(): List<String> =
        this?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    internal fun recordBackupOutcome(
        context: Context,
        tier: BackupTier,
        reason: String?,
        result: Result<String>,
    ) {
        val appContext = context.applicationContext
        val outcome = BackupAttemptOutcome(
            timestamp = System.currentTimeMillis(),
            tier = tier,
            reason = reason,
            succeeded = result.isSuccess,
            failureMessage = result.exceptionOrNull()?.message,
        )
        PreferencesManager(appContext).apply {
            lastBackupAttemptTimestamp = outcome.timestamp
            lastBackupAttemptTier = outcome.tier.name
            lastBackupAttemptReason = outcome.reason
            lastBackupAttemptSucceeded = outcome.succeeded
            lastBackupAttemptFailureMessage = outcome.failureMessage
        }
        _lastBackupOutcome.value = outcome
        // Reset here, not at each delete-song/delete-playlist call site: this is the one place
        // that already knows about every tier's outcome, matching the "ANY successful backup"
        // reset rule regardless of which call site triggered it.
        if (outcome.succeeded) DestructiveActionTracker.resetOnSuccessfulBackup(appContext)
    }

    internal fun recordExportOutcome(context: Context, result: Result<Unit>) {
        val outcome = ExportAttemptOutcome(
            timestamp = System.currentTimeMillis(),
            succeeded = result.isSuccess,
            failureMessage = result.exceptionOrNull()?.message,
        )
        PreferencesManager(context.applicationContext).apply {
            lastExportAttemptTimestamp = outcome.timestamp
            lastExportAttemptSucceeded = outcome.succeeded
            lastExportAttemptFailureMessage = outcome.failureMessage
        }
        _lastExportOutcome.value = outcome
    }

    internal fun recordVerificationOutcome(context: Context, result: Result<Unit>) {
        val outcome = VerificationAttemptOutcome(
            timestamp = System.currentTimeMillis(),
            succeeded = result.isSuccess,
            failureMessage = result.exceptionOrNull()?.message,
            skippedNoEligibleBackup = false,
        )
        PreferencesManager(context.applicationContext).apply {
            lastVerificationAttemptTimestamp = outcome.timestamp
            lastVerificationAttemptSucceeded = outcome.succeeded
            lastVerificationAttemptFailureMessage = outcome.failureMessage
            lastVerificationAttemptSkippedNoEligibleBackup = outcome.skippedNoEligibleBackup
        }
        _lastVerificationOutcome.value = outcome
    }

    /** Records "nothing eligible was available to verify", never a false success. */
    internal fun recordVerificationSkipped(context: Context) {
        val outcome = VerificationAttemptOutcome(
            timestamp = System.currentTimeMillis(),
            succeeded = false,
            failureMessage = null,
            skippedNoEligibleBackup = true,
        )
        PreferencesManager(context.applicationContext).apply {
            lastVerificationAttemptTimestamp = outcome.timestamp
            lastVerificationAttemptSucceeded = outcome.succeeded
            lastVerificationAttemptFailureMessage = outcome.failureMessage
            lastVerificationAttemptSkippedNoEligibleBackup = outcome.skippedNoEligibleBackup
        }
        _lastVerificationOutcome.value = outcome
    }

    /**
     * Records retention's own outcome, deliberately separate from
     * [recordBackupOutcome] — see [RetentionAttemptOutcome]'s doc. Logs at warn level whenever a
     * pair failed to fully delete, since this is the one place that can see both inner-swallowed
     * deletion failures [BackupRetention.prune]'s caller has no other way to surface.
     */
    internal fun recordRetentionOutcome(
        context: Context,
        tier: BackupTier,
        prune: RetentionPruneOutcome,
    ) {
        val appContext = context.applicationContext
        val outcome = RetentionAttemptOutcome(
            timestamp = System.currentTimeMillis(),
            tier = tier,
            attempted = prune.attempted,
            succeeded = prune.succeeded,
            orphanedDatabaseNames = prune.orphanedDatabaseNames,
            failedDatabaseNames = prune.failedDatabaseNames,
        )
        PreferencesManager(appContext).apply {
            lastRetentionAttemptTimestamp = outcome.timestamp
            lastRetentionAttemptTier = outcome.tier.name
            lastRetentionAttemptAttempted = outcome.attempted
            lastRetentionAttemptSucceeded = outcome.succeeded
            lastRetentionAttemptOrphanedNames = outcome.orphanedDatabaseNames.joinToString(",")
            lastRetentionAttemptFailedNames = outcome.failedDatabaseNames.joinToString(",")
        }
        _lastRetentionOutcome.value = outcome
        if (outcome.hadFailures) {
            Log.w(
                "BackupOutcomeStore",
                "Retention had failures for $tier: " +
                    "orphaned=${outcome.orphanedDatabaseNames}, failed=${outcome.failedDatabaseNames}",
            )
        }
    }

    /** Test-only: lets instrumentation reset process-scoped state between cases. */
    internal fun resetForTest() {
        initialized = false
        _lastBackupOutcome.value = null
        _lastExportOutcome.value = null
        _lastVerificationOutcome.value = null
        _lastRetentionOutcome.value = null
    }
}
