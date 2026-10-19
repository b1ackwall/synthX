package com.v1per.synthx

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.BackupManager
import com.v1per.synthx.data.backup.BackupTier
import com.v1per.synthx.data.backup.DatabaseStartupGate
import com.v1per.synthx.data.backup.MigrationInvariantFailedException
import com.v1per.synthx.data.backup.MigrationSnapshotStore
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.backup.pruneFinishedWorkIfRestoreAcknowledged
import com.v1per.synthx.data.playlistart.PlaylistArtBackfill
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.IssuesScanWorker
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.navigation.Screen
import com.v1per.synthx.playback.PlaybackViewModel
import com.v1per.synthx.preferences.PreferencesManager
import com.v1per.synthx.ui.MainScaffold
import com.v1per.synthx.ui.screens.restore.RestoreStatusViewModel
import com.v1per.synthx.ui.theme.SynthXTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The real decision [MainActivity.runLaunchMaintenance] gates on — factored to file scope
 * (rather than inlined) purely so an instrumented test can exercise the actual condition
 * without needing to launch the full Activity. See [MainActivity.runLaunchMaintenance]'s kdoc
 * for why it exists.
 */
internal fun shouldRunLaunchMaintenance(preferences: PreferencesManager): Boolean =
    // Also held off while the wizard's first scan is still pending. onboardingComplete flips
    // true when that scan starts, so without this a process-death resume into ScanningStep would
    // run the daily backup and the launch scan chain against a library mid-first-scan — the same
    // class of race the gate exists to prevent for recovery.
    preferences.onboardingComplete && preferences.firstScanRequestId == null

/**
 * Cold-start destination decision, factored to file scope for the same reason as
 * [shouldRunLaunchMaintenance]. The wizard is shown until onboarding is complete *and* no
 * first-scan request is still pending: Done marks onboarding complete at the moment it
 * starts the scan, so completion alone would send a process-death resume mid-scan to an empty
 * Home instead of back to ScanningStep. The id is cleared only when the scan is accepted
 * (success) or abandoned (Change folder).
 */
internal fun shouldShowOnboarding(preferences: PreferencesManager): Boolean =
    !preferences.onboardingComplete || preferences.firstScanRequestId != null

/**
 * The one question [MigrationRecoveryContent] hinges on — is there an internal pre-migration
 * snapshot for this build that "Restore and restart" could actually restore? Factored to file
 * scope, like [shouldShowOnboarding], so a test can exercise the real decision.
 */
internal fun isMigrationSnapshotAvailable(context: android.content.Context): Boolean =
    MigrationSnapshotStore.snapshotForCurrentVersion(context) != null

class MainActivity : ComponentActivity() {

    private var databaseStartupAttemptRunning = false

    /**
     * Resolved here rather than only inside MainScaffold so the queue restore can start
     * before first composition. ViewModelProvider with the default key returns the very
     * same instance `viewModel<PlaybackViewModel>()` picks up in the Compose tree — this
     * activity is the store owner for both.
     */
    private val playbackViewModel: PlaybackViewModel by lazy {
        ViewModelProvider(this, PlaybackViewModel.Factory(applicationContext))[
            PlaybackViewModel::class.java
        ]
    }

    /** Activity-owned for the same reason as playback: it must outlive every nav destination. */
    private val restoreStatusViewModel: RestoreStatusViewModel by lazy {
        ViewModelProvider(this, RestoreStatusViewModel.Factory(applicationContext))[
            RestoreStatusViewModel::class.java
        ]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolveDatabaseStartup()
    }

    /**
     * No production Compose tree is installed until the process-wide database gate resolves.
     * Failure content owns no repository/DAO and offers a later retry while Room stays closed.
     */
    private fun resolveDatabaseStartup() {
        if (databaseStartupAttemptRunning) return
        databaseStartupAttemptRunning = true
        lifecycleScope.launch {
            val startupFailure = runCatching { DatabaseStartupGate.awaitReady() }.exceptionOrNull()
            // Whether "Restore and restart" can actually do anything is decided here, once
            // per attempt, on IO (a prefs read plus a file check) rather than during composition.
            // Retry re-enters this function, so a changed answer is picked up on the next attempt.
            val migrationSnapshotAvailable = startupFailure is MigrationInvariantFailedException &&
                withContext(Dispatchers.IO) { isMigrationSnapshotAvailable(applicationContext) }
            // Synchronous SharedPreferences read, not a Room/DAO read — safe ahead of the
            // database gate resolving. Onboarding shows for a genuine fresh install, a
            // post-uninstall reinstall, or cleared app data alike: all three collapse to this
            // one condition. Gated on the explicit completion flag, not merely
            // musicFolderUri == null — recovery's first step sets that folder long before its
            // restore actually succeeds, and a stale-but-incomplete recovery must still
            // resume the wizard rather than bounce straight to an empty Home.
            val startDestination = if (shouldShowOnboarding(PreferencesManager(applicationContext))) {
                Screen.Onboarding.route
            } else {
                Screen.Home.route
            }
            enableEdgeToEdge()
            setContent {
                SynthXTheme {
                    when {
                        RestoreCoordinator.current() != RestoreCoordinator.Terminal.NORMAL ->
                            RestoreTerminalContent(RestoreCoordinator.current())
                        startupFailure is MigrationInvariantFailedException ->
                            MigrationRecoveryContent(
                                applicationContext = applicationContext,
                                snapshotAvailable = migrationSnapshotAvailable,
                            )
                        startupFailure != null -> DatabaseStartupFailureContent(
                            error = startupFailure,
                            onRetry = ::resolveDatabaseStartup,
                        )
                        else -> MainScaffold(
                            startDestination = startDestination,
                            restoreStatusViewModel = restoreStatusViewModel,
                        )
                    }
                }
            }
            if (startupFailure == null &&
                RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL
            ) {
                pruneFinishedWork()
                runLaunchMaintenance()
            }
            databaseStartupAttemptRunning = false
        }
    }

    /**
     * The app is single-Activity, so this is also "the app was backgrounded" — the point
     * at which the resume position has to be durable, since the process can be killed
     * from here without further warning. Chosen over adding lifecycle-process just for
     * ProcessLifecycleOwner, which would observe the same edge in this app.
     */
    override fun onStop() {
        super.onStop()
        if (DatabaseStartupGate.isReady() &&
            RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL
        ) {
            playbackViewModel.persistPlaybackState()
        }
    }

    /**
     * The foregrounding counterpart of [onStop] above, and the general-purpose recovery hook
     * for the playback cache: re-syncs the playback engine's cache against the current database state,
     * covering a missed/interrupted emission (a worker interrupted after its DB commit but
     * before it finished emitting a remap/deletion notice) that no live collector ever saw.
     * A cold-start `onStart` normally arrives while [resolveDatabaseStartup]'s asynchronous
     * inspection/migration is still running. Resolving [playbackViewModel] before that completes
     * constructs QueuePersistence -> SongRepositoryImpl -> SynthXDatabase, whose synchronous
     * factory correctly fails closed. Join the same process-wide single-flight gate here; a
     * failed startup is rendered by [resolveDatabaseStartup] and must not create playback state.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val startupReady = runCatching { DatabaseStartupGate.awaitReady() }.isSuccess
            if (startupReady &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                RestoreCoordinator.current() == RestoreCoordinator.Terminal.NORMAL
            ) {
                playbackViewModel.reconcileWithDatabase()
            }
        }
    }

    /**
     * WorkManager keeps a unique work item's terminal WorkInfo (SUCCEEDED/FAILED/CANCELLED)
     * around until it's explicitly pruned or the unique name is enqueued again — without this,
     * RestoreViewModel reading RestoreWorker.UNIQUE_WORK_NAME's WorkInfo on a later, unrelated
     * visit to the Restore screen would still see last session's SUCCEEDED state and resurface
     * the "restore complete, reopen synthX" dialog. WorkManager has no per-unique-work prune.
     * The restore-status observer now deliberately retains an unshown terminal result across
     * process death, so the guard below must approve database-wide pruning.
     */
    private suspend fun pruneFinishedWork() {
        pruneFinishedWorkIfRestoreAcknowledged(
            WorkManager.getInstance(applicationContext),
            PreferencesManager(applicationContext),
        )
    }

    /**
     * Runs only after DatabaseStartupGate has granted process-wide Room permission.
     *
     * Also gated on [PreferencesManager.onboardingComplete]. Recovery's own
     * steps persist a music folder (and sometimes a backup folder) long before a restore
     * actually succeeds — without this gate, an interrupted recovery relaunching straight back
     * into the wizard (see [resolveDatabaseStartup]) would still race this function's daily
     * backup and launch-scan chain against the just-picked folders on the very same launch,
     * publishing a snapshot of the still-empty database into the user's real Daily tier and
     * scanning/overwriting rows recovery was meant to replace. Deferring the rest of this
     * function's work (queue restore, art sweep, playlist art backfill) alongside it is
     * deliberate, not incidental: none of those have anything real to act on before onboarding
     * has actually produced a library, and by the time any of them would — a successful
     * fresh-install scan or a genuinely completed restore — [onboardingComplete] is already
     * true (recovery's [artSweepPending][PreferencesManager.artSweepPending] flag is only ever
     * set alongside it, deep inside [com.v1per.synthx.data.backup.RestoreTransaction]'s success
     * path, never before), so the next launch (or later in this same one) runs them normally.
     */
    private fun runLaunchMaintenance() {
        val preferences = PreferencesManager(applicationContext)
        if (!shouldRunLaunchMaintenance(preferences)) return
        val backupManager = BackupManager.create(applicationContext)
        // Resolved on the main thread before the coroutine: ViewModel creation (and the
        // MediaController binding it starts) belongs there, unlike the call below.
        val playback = playbackViewModel

        // Purely informational, never blocks — see BackupFolderLaunchCheck's kdoc for
        // why this can't reuse DatabaseStartupGate, and how it stays a once-per-process check
        // across Activity recreation despite this function re-running on every one of those.
        BackupFolderLaunchCheck.runOnce(applicationContext, lifecycleScope)

        lifecycleScope.launch(Dispatchers.IO) {
            backupDailyIfDue(preferences, backupManager)
            scanIssuesDailyIfDue(preferences)
            // Ahead of verifyLibrary so the mini player comes back without waiting on a full
            // existence sweep — a song that vanished since last session fails on play with
            // the normal error snackbar.
            playback.restoreSavedQueue()
            runArtSweepIfPending(preferences)
            backfillPlaylistArt()
            // Nothing to scan yet if onboarding hasn't configured a root folder — running this
            // chain here would be pointless, and pending Onboarding's own startFirstScan call
            // to enqueue LibraryScanWorker directly instead, this way it can't race it either.
            if (preferences.musicFolderUri != null) {
                runLaunchScans()
            }
        }
    }

    /**
     * Consumes [PreferencesManager.artSweepPending] — set by RestoreTransaction on the
     * cold boot right after a successful restore — and, if it was set, runs [ArtRecovery]'s
     * full-library sweep silently in the background. Cleared before the sweep runs (not after)
     * so a process death mid-sweep can't re-trigger it forever; a missed sweep is not silent
     * data loss, it just leaves whatever art it would have fixed for the Issues screen's manual
     * Fix action to pick up instead. No progress UI — unlike the manual Fix action, this pass
     * has no screen open to report to.
     */
    private suspend fun runArtSweepIfPending(preferences: PreferencesManager) {
        if (!preferences.artSweepPending) return
        preferences.artSweepPending = false
        runCatching {
            val result = SongRepositoryImpl.create(applicationContext).recoverMissingArt()
            Log.d(
                TAG,
                "Post-restore art sweep complete — checked=${result.checked} " +
                    "recovered=${result.recovered} skipped=${result.skipped}",
            )
        }.onFailure { e ->
            Log.w(TAG, "Post-restore art sweep failed: ${e.message}", e)
        }
    }

    /**
     * Gives any playlist still missing its generated cover art (rows that predate the v13->v14
     * schema, or restored from a backup that predates them) a hue and structure seed. Placed
     * after the queue restore for the same reason the art sweep is: the mini player coming back
     * matters more than a cover that is currently drawing the flat placeholder, and the playlist
     * lists redraw on their own the moment the rows land, since they observe Room.
     *
     * Unconditional every launch and a single SELECT when there is nothing to do — see
     * [PlaylistArtBackfill]. A failure here is cosmetic, never data loss, so it is logged and
     * the rest of launch maintenance continues.
     */
    private suspend fun backfillPlaylistArt() {
        runCatching { PlaylistArtBackfill.run(applicationContext) }
            .onSuccess { assigned ->
                if (assigned > 0) Log.d(TAG, "Playlist art backfill assigned $assigned playlists")
            }
            .onFailure { e -> Log.w(TAG, "Playlist art backfill failed: ${e.message}", e) }
    }

    /**
     * Two launch-time passes, chained so they run in order under LibraryScanWorker's unique
     * work name:
     *
     *  1. [VerifyLibraryWorker] — cheap existence sweep flagging songs whose files have gone.
     *     The full main-root scan stays user-triggered from Library Management.
     *  2. [NewArrivalsScanWorker] — full walk of the (small) new-arrivals tree, ingesting
     *     whatever the  companion app dropped there since last launch.
     *
     * Sharing the unique name is what keeps either of them from racing a manually-triggered
     * Rescan over `is_missing`. [ExistingWorkPolicy.KEEP] on the chain head preserves the
     * previous behaviour: a re-trigger (including the one that comes with every Activity
     * recreation) is a no-op while the chain is still running or queued.
     */
    private fun runLaunchScans() {
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                LibraryScanWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                VerifyLibraryWorker.request(),
            )
            .then(NewArrivalsScanWorker.request())
            .enqueue()
    }

    private suspend fun backupDailyIfDue(
        preferences: PreferencesManager,
        backupManager: BackupManager,
    ) {
        val last = preferences.lastDailyBackupTimestamp
        val now = System.currentTimeMillis()
        if (last != 0L && now - last < DAILY_INTERVAL_MS) return

        backupManager.createBackup(BackupTier.Daily)
            .onSuccess { preferences.lastDailyBackupTimestamp = now }
            .onFailure { e -> Log.w(TAG, "Daily backup skipped: ${e.message}") }

        // Piggybacks on this same once-per-day gate rather than a second, uncoordinated
        // timer — re-validates the most recent successful backup file itself (not just today's
        // write), catching corruption a healthy write at the time wouldn't have caught.
        backupManager.verifyMostRecentBackup()
            .onFailure { e -> Log.w(TAG, "Backup verification failed: ${e.message}") }
    }

    /**
     * The Issues screen's counterpart to [backupDailyIfDue], deliberately the same shape: one
     * persisted last-run timestamp compared against the same [DAILY_INTERVAL_MS], with a
     * never-run value (0) always counting as due.
     *
     * Differs in one respect only — the work is handed to WorkManager rather than done inline.
     * A whole-library art sweep is far too long to hold up launch maintenance, and routing it
     * through [IssuesScanWorker.UNIQUE_WORK_NAME] is also what lets the Issues screen observe
     * this automatic run and honestly report "Scanning…" if the user happens to open it while
     * this one is still going. [IssuesScanWorker] writes the timestamp itself, on completion,
     * so an interrupted or failed run stays due rather than being marked done by the enqueue.
     */
    private fun scanIssuesDailyIfDue(preferences: PreferencesManager) {
        val last = preferences.lastIssuesScanTimestamp
        val now = System.currentTimeMillis()
        if (last != 0L && now - last < DAILY_INTERVAL_MS) return

        IssuesScanWorker.enqueue(applicationContext)
    }

    private companion object {
        const val TAG = "SynthXScan"
        const val DAILY_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
