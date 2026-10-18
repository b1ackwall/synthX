package com.v1per.synthx.ui.screens.backupsandrestore

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.BackupFolderHealth
import com.v1per.synthx.data.backup.BackupInfo
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.backupsOrEmpty
import com.v1per.synthx.data.scanner.RootNesting
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val StatusMessageDisplayMs = 4_000L

/** N13/P3.1: whichever of "last successful backup" or "a more recent failed attempt" is current. */
sealed interface BackupHealthState {
    data object Unknown : BackupHealthState
    data class Succeeded(val timestamp: Long, val tier: BackupTier) : BackupHealthState
    data class Failed(val timestamp: Long, val message: String?) : BackupHealthState
}

/** N13/P3.1: the equivalent state for the playlist export sidecar. */
sealed interface ExportHealthState {
    data object Unknown : ExportHealthState
    data class Succeeded(val timestamp: Long) : ExportHealthState
    data class Failed(val timestamp: Long, val message: String?) : ExportHealthState
}

/** P3.2: only surfaced when the most recent known-good backup fails re-validation — a distinct
 * failure mode from a backup attempt itself failing, so it is not folded into [BackupHealthState]. */
data class VerificationFailure(val timestamp: Long, val message: String?)

class BackupsAndRestoreViewModel(
    // Only applicationContext is stored here (see Factory).
    @field:SuppressLint("StaticFieldLeak")
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _backupFolderUri = MutableStateFlow(
        preferencesManager.backupFolderUri?.let { it.toUri() }
    )
    val backupFolderUri: StateFlow<Uri?> = _backupFolderUri.asStateFlow()

    /** Read at each Restore click: this ViewModel can survive a trip to Library Management. */
    val musicFolderConfigured: Boolean
        get() = preferencesManager.musicFolderUri != null

    private val _backupFolderError = MutableStateFlow<String?>(null)
    val backupFolderError: StateFlow<String?> = _backupFolderError.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    /** R8-B07/P7-T6: guards [createManualBackup] against repeated taps, matching
     *  [com.v1per.synthx.ui.screens.createplaylist.CreatePlaylistViewModel]'s `_isCreating` shape. */
    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    private val _lastSuccessfulBackup = MutableStateFlow<BackupInfo?>(null)

    /** N13/P3.1: last successful backup, unless a failed attempt happened more recently. */
    val backupHealth: StateFlow<BackupHealthState> = combine(
        _lastSuccessfulBackup,
        BackupOutcomeStore.lastBackupOutcome,
    ) { success, outcome ->
        when {
            outcome != null && !outcome.succeeded && (success == null || outcome.timestamp > success.timestamp) ->
                BackupHealthState.Failed(outcome.timestamp, outcome.failureMessage)
            success != null -> BackupHealthState.Succeeded(success.timestamp, success.tier)
            else -> BackupHealthState.Unknown
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupHealthState.Unknown)

    /** N13/P3.1: last playlist-export attempt outcome. */
    val exportHealth: StateFlow<ExportHealthState> = BackupOutcomeStore.lastExportOutcome
        .map { outcome ->
            when {
                outcome == null -> ExportHealthState.Unknown
                outcome.succeeded -> ExportHealthState.Succeeded(outcome.timestamp)
                else -> ExportHealthState.Failed(outcome.timestamp, outcome.failureMessage)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportHealthState.Unknown)

    /**
     * P3.2: non-null only when the most recent known-good backup failed re-validation.
     * `skippedNoEligibleBackup` (R8-A11/P7-T5) deliberately excluded here — "nothing was
     * eligible to check" is not itself a validation failure and must not surface as one.
     */
    val verificationFailure: StateFlow<VerificationFailure?> = BackupOutcomeStore.lastVerificationOutcome
        .map { outcome ->
            outcome?.takeIf { !it.succeeded && !it.skippedNoEligibleBackup }
                ?.let { VerificationFailure(it.timestamp, it.failureMessage) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** P3.3: delete-song/delete-playlist mutations since the last successful backup. */
    val destructiveActionCount: StateFlow<Int> = DestructiveActionTracker.count

    init {
        viewModelScope.launch { refreshLastSuccessfulBackup() }
        // R8-B02/P7-T5: BackupOutcomeStore.lastBackupOutcome already reflects every
        // BackupManager.createBackup() call regardless of tier or call site — automatic/Daily
        // backups go through BackupManager directly and never touch this ViewModel otherwise.
        // Reacting to it here (rather than only refreshing from this ViewModel's own
        // createManualBackup success) is what keeps _lastSuccessfulBackup from ever going stale
        // relative to a later automatic success, without maintaining two independently-updated
        // caches that can disagree.
        viewModelScope.launch {
            BackupOutcomeStore.lastBackupOutcome
                .filterNotNull()
                .filter { it.succeeded }
                .collect { refreshLastSuccessfulBackup() }
        }
        // Re-validate on every screen entry (P7.3) — a fresh ViewModel is constructed each
        // time this screen is opened, so this doubles as "check whenever the user looks."
        // Persistent (not showTemporarily): a broken folder should stay visible until the
        // user fixes it, not vanish after 4s while nothing has actually changed.
        preferencesManager.backupFolderUri?.let { it.toUri() }?.let { uri ->
            viewModelScope.launch {
                when (BackupFolderHealth.check(context, backupManager, uri)) {
                    BackupFolderHealth.Result.Healthy -> Unit
                    BackupFolderHealth.Result.GrantLost ->
                        _backupFolderError.value =
                            context.getString(R.string.error_backup_folder_grant_lost)
                    BackupFolderHealth.Result.Unwritable ->
                        _backupFolderError.value =
                            context.getString(R.string.backup_status_folder_create_failed)
                }
            }
        }
    }

    /**
     * R8-B02/P7-T5: filters to [BackupInfo.restoreEligible] — the same cheap, already-computed
     * signal [BackupManager.verifyMostRecentBackup] filters on — so an orphan or sidecar-
     * mismatched file with a recent timestamp can no longer render as "last successful backup".
     * Deliberately does NOT run the expensive byte-level SHA verification
     * [BackupManager.verifyMostRecentBackup] does: this is read on every screen open/composition,
     * so eligibility (structural/metadata-complete) is the right-cost bar for display, not a full
     * reread+hash of the backup file.
     */
    private suspend fun refreshLastSuccessfulBackup() {
        _lastSuccessfulBackup.value = runCatching {
            backupManager.listBackups().backupsOrEmpty().firstOrNull { it.restoreEligible }
        }.getOrNull()
    }

    private fun MutableStateFlow<String?>.showTemporarily(message: String?) {
        value = message
        viewModelScope.launch {
            delay(StatusMessageDisplayMs)
            if (value == message) value = null
        }
    }

    fun setBackupFolderError(message: String) {
        _backupFolderError.value = message
    }

    /**
     * Validates before persisting anything (data 2.5): a folder that fails nesting or
     * structure validation, or throws, must never reach [PreferencesManager.backupFolderUri].
     * The whole body runs inside this one coroutine's own try/catch (general 7.4) — nothing
     * here is left to a scope that outlives the picker callback's own (now-closed) catch.
     */
    fun saveBackupFolder(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val relations = preferencesManager.configuredAudioRootUris().map { audioRoot ->
                    RootNesting.detect(context, uri, audioRoot)
                }
                if (relations.any { it == RootNesting.Relation.UNDETERMINED }) {
                    return@runCatching Outcome.NESTING_UNDETERMINED
                }
                if (relations.any { it.isConflict }) {
                    return@runCatching Outcome.NESTED
                }
                if (backupManager.ensureFolderStructure(uri)) Outcome.OK else Outcome.UNUSABLE
            }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        Outcome.OK -> {
                            preferencesManager.backupFolderUri = uri.toString()
                            _backupFolderUri.value = uri
                            _backupFolderError.value = null
                            _backupStatus.value = null
                            // R8-B02/P7-T5: a folder change must not leave the OLD folder's
                            // cached success displayed against the newly-selected folder.
                            viewModelScope.launch { refreshLastSuccessfulBackup() }
                        }
                        // Persistent (not showTemporarily), matching the same convention used
                        // for the launch-time/screen-entry health check above: a rejected pick
                        // is a real, unresolved problem and should stay visible until the user
                        // either fixes it or picks a different folder, not vanish after 4s while
                        // the row above still says "No folder selected."
                        Outcome.NESTED -> _backupFolderError.value =
                            context.getString(R.string.error_backup_folder_nested)
                        // Nesting couldn't be ruled out (opaque provider ids) rather than
                        // confirmed — fail closed with a message that doesn't overclaim.
                        Outcome.NESTING_UNDETERMINED -> _backupFolderError.value =
                            context.getString(R.string.error_backup_folder_nesting_undetermined)
                        Outcome.UNUSABLE -> _backupFolderError.value =
                            context.getString(R.string.backup_status_folder_create_failed)
                    }
                },
                onFailure = {
                    _backupFolderError.value =
                        context.getString(R.string.backup_status_folder_check_failed)
                },
            )
        }
    }

    private enum class Outcome { OK, NESTED, NESTING_UNDETERMINED, UNUSABLE }

    /**
     * R8-B07/P7-T6: guarded at entry — a tap while a backup is already in flight is a no-op, not
     * a second concurrent [BackupManager.createBackup] call. This also closes the status-text
     * race the finding described ("a later tap's backup still pending" overwriting an earlier
     * tap's reported success): with only ever one call in flight, there is no later call left to
     * race the status text against.
     */
    fun createManualBackup() {
        if (_isBackingUp.value) return
        _isBackingUp.value = true
        viewModelScope.launch {
            _backupStatus.value = context.getString(R.string.backup_status_backing_up)
            try {
                // A successful createBackup() below updates BackupOutcomeStore.lastBackupOutcome
                // (BackupManager's single outcome-recording choke point), which this ViewModel's
                // init-time collector already reacts to by refreshing _lastSuccessfulBackup — no
                // separate refresh needed here, see the init block.
                val result = backupManager.createBackup(BackupTier.Manual)
                _backupStatus.showTemporarily(
                    result.fold(
                        onSuccess = { context.getString(R.string.backup_status_created) },
                        onFailure = { e ->
                            context.getString(
                                R.string.backup_status_failed,
                                e.message ?: context.getString(R.string.scan_status_unknown_error),
                            )
                        },
                    )
                )
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(BackupsAndRestoreViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
            val appContext = context.applicationContext
            @Suppress("UNCHECKED_CAST")
            return BackupsAndRestoreViewModel(
                context = appContext,
                preferencesManager = PreferencesManager(appContext),
                backupManager = BackupManager.create(appContext),
            ) as T
        }
    }
}
