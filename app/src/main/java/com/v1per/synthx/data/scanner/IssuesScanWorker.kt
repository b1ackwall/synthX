package com.v1per.synthx.data.scanner

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.v1per.synthx.data.backup.BackupFolderLaunchCheck
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.WorkerDatabaseAccess
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.DestructiveActionTracker
import com.v1per.synthx.preferences.PreferencesManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Computes the Issues screen's full eight-category snapshot in one pass and caches the result,
 * so opening the screen is a cache read rather than a whole-library filesystem sweep.
 *
 * Six categories stay live on the screen — five are process-wide in-memory
 * StateFlows ([BackupOutcomeStore]'s three outcomes, [DestructiveActionTracker.count],
 * [BackupFolderLaunchCheck.grantLost]) and the sixth is a Room query. Missing palette data is
 * a SQL-only check, cached alongside missing art so both Fix actions use this worker's snapshot.
 * Missing album art is the expensive aggregate: it walks every song row against `filesDir/art/`
 * (see [ArtRecovery.findMissingArt]), which is what this worker exists to move off the screen's
 * critical path. Both aggregate results are persisted; the other six stay live reads on the screen,
 * where they cost nothing and are always current.
 *
 * Scheduled once a day from MainActivity's launch maintenance (the same due-check shape as the
 * daily backup) and on demand from the Issues screen's Scan button — both enqueue this same
 * work under [UNIQUE_WORK_NAME] with [ExistingWorkPolicy.KEEP], so a scan already running or
 * queued absorbs the request instead of duplicating it. That single unique name is also what
 * IssuesViewModel observes to know whether a scan is in flight, no matter who triggered it.
 *
 * Deliberately NOT enqueued under [LibraryScanWorker.UNIQUE_WORK_NAME]: this pass writes
 * nothing to the database, so it has no `is_missing` race with the three scan workers that
 * share that queue, and sharing it would both stall this behind a full folder walk and make
 * "is an issues scan running" unanswerable from the WorkInfo alone.
 *
 * Silent and unconstrained, same as [VerifyLibraryWorker] — no notification, no foreground
 * service; the Issues screen's own status area is where progress is surfaced.
 */
class IssuesScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Idempotent once-per-process guards; already run by SynthXApplication.onCreate, which
        // always precedes WorkManager dispatch. Called anyway so this worker's snapshot can
        // never silently read three unhydrated nulls if that ordering ever changes.
        BackupOutcomeStore.initialize(applicationContext)
        DestructiveActionTracker.initialize(applicationContext)

        return try {
            val (snapshot, missingPaletteCount) = WorkerDatabaseAccess.run {
                val repository = SongRepositoryImpl.create(applicationContext)
                val (paletteIds, paletteCount) = repository.getMissingPaletteSnapshotAlreadyAdmitted()
                IssuesScanSnapshot(
                    missingArtSongIds = repository.findMissingArtSongIdsAlreadyAdmitted(),
                    missingSongCount = repository.getMissingSongCountAlreadyAdmitted(),
                    missingPaletteSongIds = paletteIds,
                    grantLost = BackupFolderLaunchCheck.grantLost.value,
                    backupUnhealthy = BackupOutcomeStore.lastBackupOutcome.value?.succeeded == false,
                    exportUnhealthy = BackupOutcomeStore.lastExportOutcome.value?.succeeded == false,
                    verificationFailed = BackupOutcomeStore.lastVerificationOutcome.value?.succeeded == false,
                    destructiveActionCount = DestructiveActionTracker.count.value,
                ) to paletteCount
            }
            val issueCount = snapshot.issueCount
            val completedAt = System.currentTimeMillis()
            PreferencesManager(applicationContext).apply {
                missingArtSongIds = snapshot.missingArtSongIds.toSet()
                missingPaletteSongIds = snapshot.missingPaletteSongIds.toSet()
                lastIssuesScanMissingPaletteCount = missingPaletteCount
                lastIssuesScanIssueCount = issueCount
                // Written last: it is the timestamp both the daily due-check and the screen's
                // status line read, so it must never advance past a half-written cache.
                lastIssuesScanTimestamp = completedAt
            }

            Log.d(
                TAG,
                "Issues scan complete — issues=$issueCount missingSongs=${snapshot.missingSongCount} " +
                    "missingArt=${snapshot.missingArtSongIds.size} missingPalette=$missingPaletteCount",
            )
            Result.success(
                workDataOf(
                    KEY_ISSUE_COUNT to issueCount,
                    KEY_MISSING_ART_COUNT to snapshot.missingArtSongIds.size,
                    KEY_MISSING_PALETTE_COUNT to missingPaletteCount,
                    KEY_COMPLETED_AT to completedAt,
                ),
            )
        } catch (e: IOException) {
            // Same reasoning as VerifyLibraryWorker: a momentary SAF/storage hiccup is worth a
            // bounded retry rather than leaving the cache stale for a whole day.
            retryOrFail(e)
        } catch (e: SecurityException) {
            retryOrFail(e)
        } catch (e: Exception) {
            // Anything else (e.g. DatabaseStartupGate surfacing a real migration failure) is a
            // genuine problem — fail rather than mask it behind retries. The previous cache is
            // left untouched, so the screen keeps showing the last good scan.
            Log.e(TAG, "Issues scan failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e::class.java.simpleName)))
        }
    }

    private fun retryOrFail(e: Exception): Result {
        return if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS) {
            Log.w(TAG, "Issues scan failed transiently (attempt ${runAttemptCount + 1}), retrying", e)
            Result.retry()
        } else {
            Log.e(TAG, "Issues scan failed after $MAX_TRANSIENT_ATTEMPTS attempts, giving up", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: e::class.java.simpleName)))
        }
    }

    companion object {
        private const val TAG = "SynthXIssuesScan"

        /** Own queue, not [LibraryScanWorker.UNIQUE_WORK_NAME] — see this class's kdoc. */
        const val UNIQUE_WORK_NAME = "synthx_issues_scan"

        /** Total issue rows found. Absent on retry/failure — neither has a count to report. */
        const val KEY_ISSUE_COUNT = "issue_count"
        const val KEY_MISSING_ART_COUNT = "missing_art_count"
        const val KEY_MISSING_PALETTE_COUNT = "missing_palette_count"
        const val KEY_COMPLETED_AT = "completed_at"
        const val KEY_ERROR = "error"

        private const val MAX_TRANSIENT_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun request(): OneTimeWorkRequest =
            // No Constraints: local file and DB I/O only, nothing to gate on connectivity.
            OneTimeWorkRequestBuilder<IssuesScanWorker>()
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

        /**
         * [ExistingWorkPolicy.KEEP] by default so the daily trigger, the Scan button and the
         * post-Fix rescan all collapse into whichever run is already in flight rather than
         * queueing a second identical sweep.
         */
        fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request(),
            )
        }
    }
}
