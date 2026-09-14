package com.v1per.synthx.data.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ScanResult
import com.v1per.synthx.playback.QueuePersistence
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B restore: terminal-mode gating and the admission-permit contract — who is allowed to
 * touch live Room once the coordinator is closed, emergency, or restart-required, and that a
 * destructive restore cannot overlap an in-flight backup.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreGatingInstrumentedTest : PhaseBRestoreTestBase() {

    @Test fun emergencyPreventsRoomOpeningAndEmptyCreation() {
        RestoreCoordinator.markEmergency("incident-test")
        expectFailure { SynthXDatabase.getInstance(context) }
        assertFalse(liveFile().exists())
    }

    @Test fun workerDatabasePathBlockedInTerminalModes() = runBlocking {
        RestoreCoordinator.markRestartRequired(false)
        var roomTouches = 0
        expectSuspendFailure {
            WorkerDatabaseAccess.run(awaitStartup = {}) { roomTouches++ }
        }
        assertEquals(0, roomTouches)
    }

    @Test fun repositoryAndQueueCannotWriteAfterClose() = runBlocking {
        createLive("state-a")
        val repository = SongRepositoryImpl.create(context)
        val queue = QueuePersistence(repository)
        RestoreCoordinator.markClosed()
        expectSuspendFailure { repository.isInAnyPlaylist("missing") }
        queue.saveQueueRecords(emptyList(), null, 0L)
        queue.quiesceForRestore()
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun backupCannotOverlapDestructiveRestore() = runBlocking {
        val backupStarted = CompletableDeferred<Unit>()
        val finishBackup = CompletableDeferred<Unit>()
        val backup = launch {
            RestoreCoordinator.withDbOperation {
                BackupCreationCoordinator.serialize {
                    backupStarted.complete(Unit)
                    finishBackup.await()
                }
            }
        }
        backupStarted.await()
        val destructive = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive {
                RestoreCoordinator.awaitDrained()
                destructive.complete(Unit)
            }
        }
        yield()
        assertFalse(destructive.isCompleted)
        finishBackup.complete(Unit)
        backup.join()
        restore.await()
        assertTrue(destructive.isCompleted)
    }

    @Test fun actualFolderScannerDirectAndAlreadyAdmittedEntrypointsUseOnePermit() = runBlocking {
        val name = "phaseb-scanner-${UUID.randomUUID()}.db"
        registeredDatabaseNames += name
        val database = SynthXDatabase.createStandalone(context, name)
        database.openHelper.writableDatabase
        var executions = 0
        val export = PlaylistExportManager(
            context,
            database,
            database.playlistDao(),
            database.songDao(),
            database.artistDao(),
            database.albumDao(),
        )
        val scanner = FolderScanner(
            context,
            database,
            database.songDao(),
            database.artistDao(),
            database.albumDao(),
            database.songArtistDao(),
            export,
            scanBodyForTest = {
                executions++
                assertEquals(1, RestoreCoordinator.activeOperationCountForTest())
                ScanResult(added = 1, updated = 0, missing = 0, unchanged = 0)
            },
        )

        assertEquals(1, scanner.scan(Uri.EMPTY).added)
        RestoreCoordinator.withDbOperation {
            assertEquals(1, scanner.scanAlreadyAdmitted(Uri.EMPTY).added)
        }
        assertEquals(2, executions)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())

        RestoreCoordinator.markClosed()
        expectSuspendFailure { scanner.scan(Uri.EMPTY) }
        assertEquals(2, executions)
        database.close()
    }
}
