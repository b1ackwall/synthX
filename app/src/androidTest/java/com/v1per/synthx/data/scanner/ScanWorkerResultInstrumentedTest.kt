package com.v1per.synthx.data.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.v1per.synthx.data.backup.PhaseBRestoreTestBase
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real scan workers' own job code in normal mode, on the live debug database: what they
 * report and what they save. A test cannot hold a persisted folder grant, so the folder workers
 * only reach the "grant check passed, root still unreachable" path through [ScanFolderGrant];
 * real file ingest through a worker is not reachable without an app change.
 */
@RunWith(AndroidJUnit4::class)
class ScanWorkerResultInstrumentedTest : PhaseBRestoreTestBase() {
    private lateinit var saved: SavedPreferences
    private val realGrantCheck = ScanFolderGrant.hasReadGrant
    private val preferences get() = PreferencesManager(context)

    @Before fun isolatePreferences() {
        saved = SavedPreferences.capture(preferences)
        preferences.apply {
            musicFolderUri = null
            newArrivalsFolderUri = null
            backupFolderUri = null
        }
        createLive("original")
    }

    @After fun restorePreferences() {
        ScanFolderGrant.hasReadGrant = realGrantCheck
        saved.restore(preferences)
    }

    @Test fun libraryWorkerWithoutAFolderFails() = runBlocking {
        val result = TestListenableWorkerBuilder<LibraryScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Failure)
        assertEquals("No folder selected", result.outputData.getString(LibraryScanWorker.KEY_ERROR))
    }

    @Test fun libraryWorkerWithALostGrantForgetsOnlyTheMusicFolder() = runBlocking {
        preferences.musicFolderUri = MUSIC
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanFolderGrant.hasReadGrant = { _, _ -> false }

        val result = TestListenableWorkerBuilder<LibraryScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Failure)
        assertEquals(LibraryScanWorker.GRANT_REVOKED_MESSAGE, result.outputData.getString(LibraryScanWorker.KEY_ERROR))
        assertNull(preferences.musicFolderUri)
        assertEquals(ARRIVALS, preferences.newArrivalsFolderUri)
    }

    @Test fun libraryWorkerOnAnUnreachableRootSucceedsAndMarksNothingMissing() = runBlocking {
        preferences.musicFolderUri = MUSIC
        ScanFolderGrant.hasReadGrant = { _, _ -> true }
        seedSong("kept", "$MUSIC/document/primary%3AWorkerTest%2Fkept.mp3")

        val result = TestListenableWorkerBuilder<LibraryScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Success)
        val out = result.outputData
        assertEquals(setOf(ScanDegradation(KnownAudioRoot.Music, ScanDegradationReason.RootUnreachable)), out.scanDegradations())
        assertEquals(0, out.getInt(LibraryScanWorker.KEY_MISSING, -1))
        assertEquals(0, out.getInt(LibraryScanWorker.KEY_ADDED, -1))
        assertFalse(out.keyValueMap.containsKey(LibraryScanWorker.KEY_HIGH_IMPACT))
        assertFalse(liveSong("kept").isMissing)
        assertEquals(MUSIC, preferences.musicFolderUri)
    }

    @Test fun newArrivalsWorkerWithoutAFolderFails() = runBlocking {
        val result = TestListenableWorkerBuilder<NewArrivalsScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Failure)
        assertEquals("No folder selected", result.outputData.getString(NewArrivalsScanWorker.KEY_ERROR))
    }

    @Test fun newArrivalsWorkerWithALostGrantForgetsOnlyItsFolder() = runBlocking {
        preferences.musicFolderUri = MUSIC
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanFolderGrant.hasReadGrant = { _, _ -> false }

        val result = TestListenableWorkerBuilder<NewArrivalsScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Failure)
        assertEquals(NewArrivalsScanWorker.GRANT_REVOKED_MESSAGE, result.outputData.getString(NewArrivalsScanWorker.KEY_ERROR))
        assertNull(preferences.newArrivalsFolderUri)
        assertEquals(MUSIC, preferences.musicFolderUri)
    }

    @Test fun newArrivalsWorkerOutputHasNoMissingCount() = runBlocking {
        preferences.newArrivalsFolderUri = ARRIVALS
        ScanFolderGrant.hasReadGrant = { _, _ -> true }

        val result = TestListenableWorkerBuilder<NewArrivalsScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Success)
        val out = result.outputData
        assertFalse("missing is never reported: ${out.keyValueMap}", out.keyValueMap.containsKey(LibraryScanWorker.KEY_MISSING))
        assertEquals(0, out.getInt(LibraryScanWorker.KEY_ADDED, -1))
        assertEquals(setOf(ScanDegradation(KnownAudioRoot.NewArrivals, ScanDegradationReason.RootUnreachable)), out.scanDegradations())
    }

    @Test fun issuesWorkerSavesItsCountsAndTimeAndReportsThem() = runBlocking {
        preferences.lastIssuesScanTimestamp = 0L
        preferences.lastIssuesScanIssueCount = -1
        preferences.missingArtSongIds = setOf("stale-entry")
        seedSong("gone", "content://worker.test/gone", missing = true)
        seedSong("no-art", "content://worker.test/no-art", artUri = "file:///nowhere/art_no-art.jpg")
        seedSong("fine", "content://worker.test/fine")

        val result = TestListenableWorkerBuilder<IssuesScanWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Success)
        val out = result.outputData
        val issueCount = out.getInt(IssuesScanWorker.KEY_ISSUE_COUNT, -1)
        assertTrue("one missing song plus one art row at least: $issueCount", issueCount >= 2)
        assertEquals(1, out.getInt(IssuesScanWorker.KEY_MISSING_ART_COUNT, -1))
        assertEquals(setOf("no-art"), preferences.missingArtSongIds)
        assertEquals(issueCount, preferences.lastIssuesScanIssueCount)
        assertEquals(out.getLong(IssuesScanWorker.KEY_COMPLETED_AT, -1L), preferences.lastIssuesScanTimestamp)
        assertTrue(preferences.lastIssuesScanTimestamp > 0L)
    }

    @Test fun verifyWorkerReportsAnUnreachableRootAndMarksNothingMissing() = runBlocking {
        preferences.musicFolderUri = MUSIC
        seedSong("kept", "$MUSIC/document/primary%3AWorkerTest%2Fkept.mp3")

        val result = TestListenableWorkerBuilder<VerifyLibraryWorker>(context).build().doWork()

        assertTrue("$result", result is ListenableWorker.Result.Success)
        val out = result.outputData
        assertEquals(0, out.getInt(VerifyLibraryWorker.KEY_NEWLY_MISSING, -1))
        assertEquals(setOf(ScanDegradation(KnownAudioRoot.Music, ScanDegradationReason.RootUnreachable)), out.scanDegradations())
        assertFalse(liveSong("kept").isMissing)
    }

    private suspend fun seedSong(id: String, path: String, missing: Boolean = false, artUri: String? = null) {
        SynthXDatabase.getInstance(context).songDao().upsertSong(
            SongEntity(
                id = id,
                title = id,
                albumId = null,
                duration = 1,
                filePath = path,
                albumArtUri = artUri,
                year = null,
                dateAdded = 1,
                isMissing = missing,
                missingSince = if (missing) 1L else null,
            )
        )
    }

    private suspend fun liveSong(id: String): SongEntity =
        requireNotNull(SynthXDatabase.getInstance(context).songDao().getSongById(id)) { "song $id gone" }

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
        const val MUSIC = "content://com.android.externalstorage.documents/tree/primary%3ASlice5aMusic"
        const val ARRIVALS = "content://com.android.externalstorage.documents/tree/primary%3ASlice5aArrivals"
    }
}
