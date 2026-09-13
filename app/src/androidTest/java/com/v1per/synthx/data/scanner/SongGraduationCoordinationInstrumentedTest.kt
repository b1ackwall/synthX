package com.v1per.synthx.data.scanner

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.FileOperationCoordinator
import com.v1per.synthx.data.ScanRowClaims
import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.data.db.SongEntity
import com.v1per.synthx.data.db.SynthXDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real [graduateSong] against a real in-memory Room database.
 *
 * A full move can't run here — it needs persisted SAF grants on two trees, which an
 * instrumentation test can't obtain without the system picker. Instead the song's row points at a
 * content URI no provider serves, so graduation takes its "Source file could not be found" exit.
 * That exit is reached *inside* the song lock, the restore permit and the root usage, and reports
 * through `onStage` while still holding all three — so suspending in `onStage` holds a genuine,
 * in-flight graduation open at an exact point, the same role `afterBackup` plays in
 * RepositoryDeletionInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class SongGraduationCoordinationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: SynthXDatabase

    private val songId = "song"
    private val oldPath = "content://com.v1per.synthx.test.nonexistent/document/old"
    private val musicRoot = Uri.parse("content://com.v1per.synthx.test.nonexistent/tree/music")

    @Before fun setUp() = runBlocking {
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
        database = Room.inMemoryDatabaseBuilder(context, SynthXDatabase::class.java).build()
        database.songDao().upsertSong(
            SongEntity(
                id = songId, title = "Song", albumId = null, duration = 1,
                filePath = oldPath, albumArtUri = null, year = null, dateAdded = 1,
            ).copy(isStarred = true),
        )
    }

    @After fun tearDown() {
        database.close()
        RestoreCoordinator.resetForTest()
        FileOperationCoordinator.resetForTest()
    }

    /**
     * Scan-during-graduation. The scan began (opened its claims) before graduation, graduation is
     * in flight, and the scan's commit reaches this song: it must be refused both while graduation
     * holds the song and after it has finished, and the row must keep graduation's state rather
     * than the scan's stale extraction (old path, re-starred).
     */
    @Test fun scanCommitSkipsASongWhileAndAfterItIsGraduated() = runBlocking {
        val scanClaims = ScanRowClaims()
        // Closed even on assertion failure: a leaked lease would fail every later test's reset.
        try {
            assertScanCommitSkipped(scanClaims)
        } finally {
            scanClaims.close()
        }
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
        assertEquals(0, FileOperationCoordinator.rootUserCountForTest(FileOperationCoordinator.rootKey(musicRoot)))
    }

    private suspend fun assertScanCommitSkipped(scanClaims: ScanRowClaims) {
        val claimedDuringGraduation = CompletableDeferred<Boolean>()
        val permitsDuringGraduation = CompletableDeferred<Int>()

        val result = withTimeout(5_000) {
            graduateSong(context, database, database.songDao(), songId, musicRoot) { stage ->
                if (stage is GraduationStage.Failed) {
                    permitsDuringGraduation.complete(RestoreCoordinator.activeOperationCountForTest())
                    // Exactly the scan write loop's guard for a row it wants to rewrite.
                    val claimed = scanClaims.claim(songId)
                    if (claimed) {
                        val row = checkNotNull(database.songDao().getSongById(songId))
                        database.songDao().upsertSong(row.copy(filePath = "content://stale/extraction"))
                    }
                    claimedDuringGraduation.complete(claimed)
                }
            }
        }

        assertEquals(GraduationResult.Failure("Source file could not be found"), result)
        assertFalse("scan must not claim a song graduation holds", claimedDuringGraduation.await())
        assertEquals("graduation holds a restore permit while it runs", 1, permitsDuringGraduation.await())
        assertEquals(oldPath, database.songDao().getSongById(songId)?.filePath)

        // Graduation is over and its lock is free, but it mutated the song after the scan began.
        assertFalse("stale extraction must not commit after graduation", scanClaims.claim(songId))
        assertEquals(2, scanClaims.deferred)
    }

    /** Exposure 9: a restore starting mid-graduation drains it instead of closing under it. */
    @Test fun restoreWaitsForAnInFlightGraduationToFinish() = runBlocking {
        val graduationHolding = CompletableDeferred<Unit>()
        val finishGraduation = CompletableDeferred<Unit>()
        val graduation = async(Dispatchers.IO) {
            graduateSong(context, database, database.songDao(), songId, musicRoot) { stage ->
                if (stage is GraduationStage.Failed) {
                    graduationHolding.complete(Unit)
                    finishGraduation.await()
                }
            }
        }
        withTimeout(5_000) { graduationHolding.await() }

        val restoreDrained = CompletableDeferred<Unit>()
        val restore = async(Dispatchers.IO) {
            RestoreCoordinator.exclusive {
                RestoreCoordinator.awaitDrained()
                restoreDrained.complete(Unit)
            }
        }
        delay(200)
        assertFalse("restore must not drain past a running graduation", restoreDrained.isCompleted)

        finishGraduation.complete(Unit)
        withTimeout(5_000) {
            graduation.await()
            restore.await()
        }
        assertTrue(restoreDrained.isCompleted)
    }

    @Test fun graduationIsRefusedOnceRestoreHasClosedAdmission() = runBlocking {
        RestoreCoordinator.exclusive { }
        val stages = mutableListOf<GraduationStage>()
        val result = graduateSong(context, database, database.songDao(), songId, musicRoot) { stages += it }

        assertEquals(GraduationResult.Failure(GRADUATION_RESTORE_IN_PROGRESS_REASON), result)
        assertEquals(listOf<GraduationStage>(GraduationStage.Failed(GRADUATION_RESTORE_IN_PROGRESS_REASON)), stages)
        assertEquals(0, FileOperationCoordinator.songLockEntryCountForTest())
    }

    @Test fun graduationIsRefusedWhileItsDestinationRootIsBeingRetired() = runBlocking {
        val musicKey = FileOperationCoordinator.rootKey(musicRoot)
        val blocker = checkNotNull(FileOperationCoordinator.acquireRoot(musicKey))
        val retirement = async(Dispatchers.IO) {
            FileOperationCoordinator.retireRootWhenIdle(musicKey) { }
        }
        withTimeout(5_000) {
            while (FileOperationCoordinator.acquireRoot(musicKey)?.also { it.release() } != null) delay(10)
        }

        val result = graduateSong(context, database, database.songDao(), songId, musicRoot)

        assertEquals(GraduationResult.Failure(GRADUATION_FOLDER_CHANGING_REASON), result)
        blocker.release()
        withTimeout(5_000) { retirement.await() }
    }

    @Test fun commitRepointsTheRowInsideOneTransaction() = runBlocking {
        val commit = commitGraduatedPath(database, database.songDao(), songId, "content://library/Song.mp3")

        assertTrue(commit is GraduationCommit.Updated)
        val row = checkNotNull(database.songDao().getSongById(songId))
        assertEquals("content://library/Song.mp3", row.filePath)
        assertFalse(row.isStarred)
    }

    @Test fun commitReportsRowGoneWithoutRecreatingIt() = runBlocking {
        database.songDao().deleteSongById(songId)

        val commit = commitGraduatedPath(database, database.songDao(), songId, "content://library/Song.mp3")

        assertEquals(GraduationCommit.RowGone, commit)
        assertEquals(null, database.songDao().getSongById(songId))
    }
}
