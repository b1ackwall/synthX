package com.v1per.synthx.data.scanner

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.backup.WorkerDatabaseAccess
import com.v1per.synthx.preferences.PreferencesManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Launch-time "is anything gone" sweep — a cheap existence check against songs already
 * known to the DB, as opposed to [LibraryScanWorker]'s full folder walk. Both flag
 * `is_missing` against the same rows, so they cannot be allowed to run concurrently: enqueued
 * under [LibraryScanWorker.UNIQUE_WORK_NAME] with [ExistingWorkPolicy.KEEP], the same
 * unique-work queue that serializes Rescan against itself serializes verify
 * against Rescan too — whichever is already running wins, the other enqueue is a no-op.
 *
 * No [androidx.work.ForegroundInfo]/notification here, unlike [LibraryScanWorker] — that
 * machinery exists so a long folder walk survives Doze/App Standby; this pass is bounded and
 * fast, so it stays a plain background worker and stays silent.
 */
class VerifyLibraryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val preferences = PreferencesManager(applicationContext)
        val configuredRoots = listOfNotNull(
            preferences.musicFolderUri?.let {
                ConfiguredAudioRoot(it.toUri(), KnownAudioRoot.Music)
            },
            preferences.newArrivalsFolderUri?.let {
                ConfiguredAudioRoot(it.toUri(), KnownAudioRoot.NewArrivals)
            },
        )
        return try {
            val result = WorkerDatabaseAccess.run {
                SongRepositoryImpl.create(applicationContext)
                    .verifyLibrary(configuredRoots)
            }
            Log.d(TAG, "Launch verify complete: ${result.newlyMissing} newly missing")
            Result.success(verifyOutputData(result))
        } catch (e: IOException) {
            // Plausibly a momentary SAF/content-provider hiccup (e.g. the provider process
            // hasn't finished warming up right after boot) rather than a real defect — worth
            // one retry rather than silently blocking NewArrivalsScanWorker for the rest of
            // this launch. Capped below so a *persistent* IO failure still surfaces instead
            // of retrying forever.
            retryOrFail(e)
        } catch (e: SecurityException) {
            // Same reasoning as IOException — a transient permission/grant hiccup, not
            // necessarily a genuine revocation (FolderScanner's own reachability checks
            // handle a real revocation without throwing at all).
            retryOrFail(e)
        } catch (e: Exception) {
            // Anything else (e.g. DatabaseStartupGate surfacing a real migration failure) is
            // a genuine problem, not a transient blip — fail immediately rather than masking
            // it behind retries.
            Log.e(TAG, "Launch verify failed", e)
            Result.failure()
        }
    }

    /**
     * Retries a plausibly-transient failure, but only up to [MAX_TRANSIENT_ATTEMPTS] times —
     * beyond that a failure that keeps recurring is no longer "transient" in any useful sense,
     * and retrying indefinitely (WorkManager has no built-in attempt cap) would just mask a
     * real, persistent problem instead of ever surfacing it.
     */
    private fun retryOrFail(e: Exception): Result {
        return if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS) {
            Log.w(TAG, "Launch verify failed transiently (attempt ${runAttemptCount + 1}), retrying", e)
            Result.retry()
        } else {
            Log.e(TAG, "Launch verify failed after $MAX_TRANSIENT_ATTEMPTS attempts, giving up", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SynthXScan"

        /** Count of songs newly flagged missing this pass — absent on retry/failure, since
         *  neither path has a meaningful count to report. */
        const val KEY_NEWLY_MISSING = "newly_missing"

        /** Launch-time check has time to spare — this is not meant to retry aggressively. */
        private const val MAX_TRANSIENT_ATTEMPTS = 3
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun request(): OneTimeWorkRequest =
            // No Constraints: pure local file I/O against the DB, no network dependency —
            // nothing here to gate on connectivity, battery, or storage state.
            OneTimeWorkRequestBuilder<VerifyLibraryWorker>()
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                LibraryScanWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request(),
            )
        }
    }
}
