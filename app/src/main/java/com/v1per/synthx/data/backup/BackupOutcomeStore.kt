package com.v1per.synthx.data.backup

import android.content.Context
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
 * [skippedNoEligibleBackup]: R8-A11/P7-T5 — true only when [BackupManager.verifyMostRecentBackup]
 * found no [BackupInfo.restoreEligible] entry to check at all (no backup yet, or every listed
 * entry is ineligible). Distinct from [succeeded]`= true` (a real byte-level check ran and
 * passed) and from [succeeded]`= false` with this flag clear (a real check ran and failed) —
 * collapsing "nothing to verify" into either of those was the false-assurance bug this splits
 * apart. [succeeded] is always `false` alongside this flag; callers that only care whether a
 * real failure occurred should check `!succeeded && !skippedNoEligibleBackup`.
 */
data class VerificationAttemptOutcome(
    val timestamp: Long,
    val succeeded: Boolean,
    val failureMessage: String?,
    val skippedNoEligibleBackup: Boolean = false,
)

/**
 * N13: the missing half of Issue 6.2's [BackupFolderHealth]. That answers "can I write to the
 * backup folder right now" — a pre-condition check. This answers "did the last actual backup or
 * export attempt succeed, and when" — the thing that was never recorded anywhere before this.
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
    }

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

    /** R8-A11/P7-T5: records "nothing eligible was available to verify", never a false success. */
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

    /** Test-only: lets instrumentation reset process-scoped state between cases. */
    internal fun resetForTest() {
        initialized = false
        _lastBackupOutcome.value = null
        _lastExportOutcome.value = null
        _lastVerificationOutcome.value = null
    }
}
