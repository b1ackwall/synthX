package com.v1per.synthx.data.scanner

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v1per.synthx.data.repository.SongRepositoryImpl

/**
 * Launch-time "is anything gone" sweep — a cheap existence check against songs already
 * known to the DB, as opposed to [LibraryScanWorker]'s full folder walk. Both flag
 * `is_missing` against the same rows, so they cannot be allowed to run concurrently: enqueued
 * under [LibraryScanWorker.UNIQUE_WORK_NAME] with [ExistingWorkPolicy.KEEP], the same
 * unique-work queue that already serializes Rescan against itself now serializes verify
 * against Rescan too — whichever is already running wins, the other enqueue is a no-op.
 *
 * No [androidx.work.ForegroundInfo]/notification here, unlike [LibraryScanWorker] — that
 * machinery exists so a long folder walk survives Doze/App Standby; this pass is bounded and
 * fast, so it stays a plain background worker and stays silent, matching what it did as a
 * bare `lifecycleScope` coroutine before this was folded into WorkManager.
 */
class VerifyLibraryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val newlyMissing = SongRepositoryImpl.create(applicationContext).verifyLibrary()
            Log.d(TAG, "Launch verify complete: $newlyMissing newly missing")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Launch verify failed", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "SynthXScan"

        fun request(): OneTimeWorkRequest =
            // No Constraints: pure local file I/O against the DB, no network dependency —
            // nothing here to gate on connectivity, battery, or storage state.
            OneTimeWorkRequestBuilder<VerifyLibraryWorker>()
                .setConstraints(Constraints.NONE)
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
