package com.v1per.synthx.data.scanner

import android.annotation.SuppressLint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.v1per.synthx.data.backup.BackupOutcomeStore
import com.v1per.synthx.data.backup.PhaseBRestoreTestBase
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real scan workers run while a restore holds the database closed. Each one must be refused
 * by the restore admission gate instead of reading or writing Room. The two folder workers need
 * the [ScanFolderGrant] seam, because a test cannot hold a persisted folder grant.
 */
// Test-only restricted API use; there is no public equality-based alternative.
@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class ScanWorkerRestoreAdmissionInstrumentedTest : PhaseBRestoreTestBase() {
    private lateinit var saved: SavedPreferences
    private val realGrantCheck = ScanFolderGrant.hasReadGrant

    @Before fun isolatePreferences() {
        saved = SavedPreferences.capture(PreferencesManager(context))
        PreferencesManager(context).apply {
            musicFolderUri = null
            newArrivalsFolderUri = null
            backupFolderUri = null
        }
        createLive("original")
        SongRepositoryImpl.create(context)
        BackupOutcomeStore.resetForTest()
    }

    @After fun restorePreferences() {
        ScanFolderGrant.hasReadGrant = realGrantCheck
        saved.restore(PreferencesManager(context))
        // The real library worker records a refused pre-scan backup here; it must not outlive the test.
        BackupOutcomeStore.resetForTest()
    }

    @Test fun issuesScanWorkerIsRefusedWhileRestoreIsPreparing() = runBlocking {
        val result = runWhileRestoreIsPreparing(TestListenableWorkerBuilder<IssuesScanWorker>(context).build())

        assertTrue("worker ran: $result", result is ListenableWorker.Result.Failure)
        assertEquals(RESTORE_REFUSAL, result.outputData.getString(IssuesScanWorker.KEY_ERROR))
    }

    @Test fun issuesScanWorkerSucceedsInNormalMode() = runBlocking {
        val result = TestListenableWorkerBuilder<IssuesScanWorker>(context).build().doWork()

        assertTrue("worker failed: $result", result is ListenableWorker.Result.Success)
    }

    @Test fun verifyLibraryWorkerIsRefusedWhileRestoreIsPreparing() = runBlocking {
        val result = runWhileRestoreIsPreparing(TestListenableWorkerBuilder<VerifyLibraryWorker>(context).build())

        assertTrue("worker ran: $result", result is ListenableWorker.Result.Failure)
    }

    @Test fun verifyLibraryWorkerSucceedsInNormalMode() = runBlocking {
        val result = TestListenableWorkerBuilder<VerifyLibraryWorker>(context).build().doWork()

        assertTrue("worker failed: $result", result is ListenableWorker.Result.Success)
    }

    @Test fun libraryScanWorkerIsRefusedWhileRestoreIsPreparing() = runBlocking {
        PreferencesManager(context).musicFolderUri = FAKE_FOLDER
        ScanFolderGrant.hasReadGrant = { _, _ -> true }

        val result = runWhileRestoreIsPreparing(TestListenableWorkerBuilder<LibraryScanWorker>(context).build())

        assertTrue("worker ran: $result", result is ListenableWorker.Result.Failure)
        assertEquals(RESTORE_REFUSAL, result.outputData.getString(LibraryScanWorker.KEY_ERROR))
    }

    @Test fun newArrivalsScanWorkerIsRefusedWhileRestoreIsPreparing() = runBlocking {
        PreferencesManager(context).newArrivalsFolderUri = FAKE_FOLDER
        ScanFolderGrant.hasReadGrant = { _, _ -> true }

        val result = runWhileRestoreIsPreparing(TestListenableWorkerBuilder<NewArrivalsScanWorker>(context).build())

        assertTrue("worker ran: $result", result is ListenableWorker.Result.Failure)
        assertEquals(RESTORE_REFUSAL, result.outputData.getString(NewArrivalsScanWorker.KEY_ERROR))
    }

    private suspend fun runWhileRestoreIsPreparing(worker: CoroutineWorker): ListenableWorker.Result {
        val result = RestoreCoordinator.exclusive {
            assertEquals(RestoreCoordinator.Mode.RESTORE_PREPARING, RestoreCoordinator.currentMode())
            worker.doWork()
        }
        RestoreCoordinator.markNormal()
        return result
    }

    /** Preferences these workers read or write, put back after each test. */
    private class SavedPreferences(
        val musicFolderUri: String?,
        val newArrivalsFolderUri: String?,
        val backupFolderUri: String?,
        val missingArtSongIds: Set<String>,
        val lastIssuesScanIssueCount: Int,
        val lastIssuesScanTimestamp: Long,
    ) {
        fun restore(preferences: PreferencesManager) {
            preferences.musicFolderUri = musicFolderUri
            preferences.newArrivalsFolderUri = newArrivalsFolderUri
            preferences.backupFolderUri = backupFolderUri
            preferences.missingArtSongIds = missingArtSongIds
            preferences.lastIssuesScanIssueCount = lastIssuesScanIssueCount
            preferences.lastIssuesScanTimestamp = lastIssuesScanTimestamp
        }

        companion object {
            fun capture(preferences: PreferencesManager) = SavedPreferences(
                preferences.musicFolderUri,
                preferences.newArrivalsFolderUri,
                preferences.backupFolderUri,
                preferences.missingArtSongIds,
                preferences.lastIssuesScanIssueCount,
                preferences.lastIssuesScanTimestamp,
            )
        }
    }

    private companion object {
        const val RESTORE_REFUSAL = "Database operation is unavailable during restore"
        const val FAKE_FOLDER = "content://com.android.externalstorage.documents/tree/primary%3ASlice2Test"
    }
}
