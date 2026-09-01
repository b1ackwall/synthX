package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.backup.WorkerDatabaseAccess
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.CancellationException

/**
 * Full folder walk over the *new-arrivals* tree — the second SAF root the  companion
 * app drops downloads into. Runs on every launch, unlike [LibraryScanWorker], because the
 * tree is expected to be small and its whole point is that files appear in it between
 * sessions. Anything it finds becomes an ordinary library song, surfaced by the existing
 * RECENTLY_ADDED sort; nothing marks it as having come from here.
 *
 * Deliberately trimmed relative to [LibraryScanWorker]:
 *  - `reconcileMissing = false` — the missing-detection pass compares the whole library
 *    against the tree being scanned, which would flag every main-root song missing.
 *  - No pre-scan backup. This pass only ever adds/updates rows for files that are physically
 *    present, so there is nothing destructive to snapshot ahead of.
 *  - No foreground notification. Bounded and fast, same reasoning as [VerifyLibraryWorker].
 *
 * Shares [LibraryScanWorker.UNIQUE_WORK_NAME] with the other two scan workers, so it can
 * never run concurrently with a pass that writes `is_missing` against the same rows.
 *
 * The folder URI is read from preferences at execution time rather than passed as input
 * data, so a re-enqueued run always picks up the current folder.
 */
class NewArrivalsScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val preferences = PreferencesManager(applicationContext)
        val uriString = preferences.newArrivalsFolderUri
            // Expected and normal until the folder is ever picked — not an error state.
            ?: return Result.failure(workDataOf(KEY_ERROR to "No folder selected"))
        val uri = Uri.parse(uriString)

        // A stored URI outlives its grant (revoked access, restored backup). Same handling
        // as LibraryScanWorker: drop the stale URI so the UI falls back to "No folder
        // selected" rather than silently scanning nothing forever.
        val grantValid = applicationContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!grantValid) {
            preferences.newArrivalsFolderUri = null
            return Result.failure(workDataOf(KEY_ERROR to GRANT_REVOKED_MESSAGE))
        }

        return try {
            val result = WorkerDatabaseAccess.run {
                SongRepositoryImpl.create(applicationContext)
                    .scanLibrary(uri, reconcileMissing = false, isNewArrivalsRoot = true)
            }
            Log.d(TAG, "New arrivals scan complete: ${result.added} added, ${result.updated} updated")
            Result.success(
                workDataOf(
                    LibraryScanWorker.KEY_ADDED to result.added,
                    LibraryScanWorker.KEY_UPDATED to result.updated,
                    LibraryScanWorker.KEY_UNCHANGED to result.unchanged,
                )
            )
        } catch (e: CancellationException) {
            // Let WorkManager see it — the Room transaction has already rolled back.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "New arrivals scan failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "New arrivals scan failed")))
        }
    }

    companion object {
        private const val TAG = "SynthXScan"

        const val KEY_ERROR = LibraryScanWorker.KEY_ERROR

        const val GRANT_REVOKED_MESSAGE =
            "Access to your new arrivals folder has been revoked. Please select it again."

        fun request(): OneTimeWorkRequest =
            // No Constraints: pure local SAF file I/O, no network dependency — nothing
            // here to gate on connectivity, battery, or storage state.
            OneTimeWorkRequestBuilder<NewArrivalsScanWorker>()
                .setConstraints(Constraints.NONE)
                .build()

        /**
         * Standalone enqueue, used when the folder is first picked so it gets scanned without
         * waiting for the next launch. [policy] defaults to [ExistingWorkPolicy.KEEP] — never
         * REPLACE: the shared unique name may be carrying an in-flight main-root scan, and this
         * pass is not important enough to cancel one.
         *
         * [LibraryManagementViewModel.saveNewArrivalsFolder] passes
         * [ExistingWorkPolicy.APPEND_OR_REPLACE] instead: KEEP would silently discard this
         * request if another scan (a manual rescan, or the launch-time
         * VerifyLibraryWorker->NewArrivalsScanWorker chain) is currently running or queued
         * under this same unique name — see general 6.4 in the final-sweep audit. APPEND_OR_REPLACE
         * queues this request to run once that work finishes rather than dropping it, while still
         * falling back to a fresh enqueue (same as KEEP/REPLACE would) if nothing is currently
         * running. It's the "_OR_REPLACE" variant rather than plain APPEND specifically because
         * plain APPEND immediately fails/cancels the new request too if the chain it would
         * append to already finished in a FAILED or CANCELLED state — APPEND_OR_REPLACE starts
         * fresh in that case instead of inheriting a stale failure.
         */
        fun enqueue(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                LibraryScanWorker.UNIQUE_WORK_NAME,
                policy,
                request(),
            )
        }
    }
}
