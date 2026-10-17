package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B restore: the live destructive transaction itself — successful install, WAL handling,
 * automatic rollback, and every injected fault point along the way.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreTransactionInstrumentedTest : PhaseBRestoreTestBase() {

    @Test fun emergencyReplacementUsesScopedClosedModePath() = runBlocking {
        liveFile().writeText("unusable live database")
        val source = newTrustedSource("emergency replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val result = RestoreTransaction.executeEmergencyReplacement(
            context,
            backupInfo(source),
            metadata(source),
            UUID.randomUUID().toString(),
        )

        assertTrue(result.isSuccess)
        assertEquals("emergency replacement", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Terminal.RESTART_REQUIRED, RestoreCoordinator.current())
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, readState().phase)
    }

    @Test fun safetySnapshotFailureLeavesLiveUntouched() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val result = execute(source, metadata(source), failAt(RestoreFaultPoint.SAFETY_SNAPSHOT))
        assertTrue(result.isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun walCommittedDataPreservedInOldSnapshot() = runBlocking {
        createLive("initial")
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
        // This test proves an update to the existing "marker" row survives via WAL, so it must
        // update that row, not insert a second one. createLive("initial") already committed a
        // "marker"-id row into this same live database — group()'s default id also being
        // "marker" made the old insertGroup(group("wal-committed")) here a same-id INSERT
        // against an already-occupied primary key, which insertGroup's OnConflictStrategy.ABORT
        // (deliberately not REPLACE, see PlaylistDao.insertGroup's kdoc) rejects outright. Pre
        // P3-T2's REPLACE, this silently deleted-and-reinserted the row instead of throwing -
        // renameGroup is the correct, already-existing way to update it in place.
        room.playlistDao().renameGroup(id = "marker", name = "wal-committed")
        val wal = File(liveFile().path + "-wal")
        assertTrue(wal.isFile && wal.length() > 0)
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source)).isSuccess)
        val old = databaseDir.listFiles().orEmpty().single { it.name.endsWith(".old.db") }
        assertEquals("wal-committed", readMarker(old))
    }

    @Test fun staleLiveBasenameWalCannotAttachToCandidate() = runBlocking {
        createLive("state-a")
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").close()
        // Same fix as walCommittedDataPreservedInOldSnapshot above: this must update the
        // existing "marker" row (to prove a stale/uncommitted change doesn't leak into the
        // restored candidate), not insert an id-colliding duplicate.
        room.playlistDao().renameGroup(id = "marker", name = "old-wal")
        assertTrue(File(liveFile().path + "-wal").isFile)
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source)).isSuccess)
        assertEquals("state-b", readMarker(liveFile()))
        assertNotEquals("old-wal", readMarker(liveFile()))
    }

    @Test fun liveToOldRenameWorks() = runBlocking {
        val outcome = successfulRestore()
        assertTrue(outcome.old.isFile)
        assertEquals("state-a", readMarker(outcome.old))
    }

    @Test fun candidateToLiveRenameWorks() = runBlocking {
        successfulRestore()
        assertEquals("state-b", readMarker(liveFile()))
    }

    @Test fun installedLiveHasExactCandidateShaAndSize() = runBlocking {
        val outcome = successfulRestore()
        assertEquals(outcome.state.candidateSize, liveFile().length())
        assertEquals(outcome.state.candidateSha256, BackupHashing.hash(liveFile()).sha256)
    }

    @Test fun installedLivePassesStructuralValidation() = runBlocking {
        successfulRestore()
        assertEquals(SynthXDatabase.VERSION, BackupSnapshotValidator.validate(liveFile(), false).schema.userVersion)
    }

    @Test fun injectedFailureAfterLiveMovedIsTerminal() = runBlocking {
        val result = restoreWithFailure(RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME)
        assertTrue(result.isFailure)
        assertEquals(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED, RestoreCoordinator.current())
    }

    @Test fun automaticOldRollbackAfterLiveMovedFailure() = runBlocking {
        restoreWithFailure(RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(SynthXDatabase.VERSION, BackupSnapshotValidator.validate(liveFile(), false).schema.userVersion)
    }

    @Test fun injectedFailureAfterCandidateInstallIsTerminal() = runBlocking {
        val result = restoreWithFailure(RestoreFaultPoint.INSTALLED_VALIDATION)
        assertTrue(result.isFailure)
        assertEquals(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED, RestoreCoordinator.current())
    }

    @Test fun automaticOldRollbackAfterCandidateInstallFailure() = runBlocking {
        restoreWithFailure(RestoreFaultPoint.INSTALLED_VALIDATION)
        assertEquals("state-a", readMarker(liveFile()))
    }

    @Test fun invalidOldUsesSafetyFallback() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val faults = RestoreFaultInjector { point ->
            if (point == RestoreFaultPoint.INSTALLED_VALIDATION) {
                databaseDir.listFiles().orEmpty().single { it.name.endsWith(".old.db") }
                    .writeText("corrupt old")
                error("fail after candidate install")
            }
        }
        val result = execute(source, metadata(source), faults)
        assertTrue(result.isFailure)
        assertEquals(
            "terminal=${RestoreCoordinator.current()} failure=${result.exceptionOrNull()?.stackTraceToString()} " +
                "files=${databaseDir.listFiles().orEmpty().map { it.name }}",
            "state-a",
            readMarker(liveFile()),
        )
    }

    @Test fun safetyFallbackRecoveredLiveValidates() = runBlocking {
        invalidOldUsesSafetyFallback()
        assertEquals(SynthXDatabase.VERSION, BackupSnapshotValidator.validate(liveFile(), false).schema.userVersion)
    }

    @Test fun injectedSourceReadFailureLeavesLiveNormal() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source), failAt(RestoreFaultPoint.SOURCE_STREAM_READ)).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun injectedMigrationFailureLeavesLiveNormal() = runBlocking {
        createLive("state-a")
        val olderName = validBackupName()
        registeredDatabaseNames += olderName
        migrationHelper.createDatabase(olderName, 7).close()
        val source = context.getDatabasePath(olderName)
        val digest = BackupHashing.hash(source)
        val olderMetadata = metadata(source).copy(
            dbVersion = 7,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
        assertTrue(execute(source, olderMetadata, failAt(RestoreFaultPoint.MIGRATION)).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun injectedFreeSpaceFailureIsDeterministic() {
        var observed = false
        expectFailure(IllegalStateException::class, "injected free space") {
            RestoreTransaction.ensureSpace(
                databaseDir,
                1,
                1,
                RestoreFaultInjector { point ->
                    if (point == RestoreFaultPoint.FREE_SPACE_PREFLIGHT) {
                        observed = true
                        error("injected free space")
                    }
                },
                Long.MAX_VALUE,
            )
        }
        assertTrue(observed)
    }

    /**
     * P7-T7/R7-F17: [RestoreTransaction.execute]'s `onProgress` callback is what
     * [RestoreWorker] threads into its (previously permanently-indeterminate,
     * `setProgress(0, 0, true)`) foreground notification. Asserts the exact, ordered
     * sequence a successful restore reports.
     */
    @Test fun executeReportsDistinctProgressStepsInOrder() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val steps = mutableListOf<RestoreProgressStep>()

        val result = RestoreTransaction.execute(
            context,
            backupInfo(source),
            metadata(source),
            UUID.randomUUID().toString(),
            onProgress = { steps += it },
        )

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                RestoreProgressStep.COPYING,
                RestoreProgressStep.VALIDATING,
                RestoreProgressStep.INSTALLING,
                RestoreProgressStep.FINISHING,
            ),
            steps,
        )
    }

    @Test fun injectedStatePersistenceFailureLeavesLiveNormal() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source), failAt(RestoreFaultPoint.STATE_PERSISTENCE)).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun journalDirectorySyncFailureAbortsBeforeLiveRename() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")

        val result = execute(
            source,
            metadata(source),
            failAt(RestoreFaultPoint.STATE_JOURNAL_DIRECTORY_SYNC),
        )

        assertTrue(result.isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertTrue(databaseDir.listFiles().orEmpty().none { it.name.endsWith(".old.db") })
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun preCloseCleanupSyncFailureEndsInEmergencyRatherThanPreparing() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val faults = RestoreFaultInjector { point ->
            if (point == RestoreFaultPoint.SAFETY_SNAPSHOT ||
                point == RestoreFaultPoint.PRE_CLOSE_FAILURE_CLEANUP_DIRECTORY_SYNC
            ) error("Injected $point")
        }

        val result = execute(source, metadata(source), faults)

        assertTrue(result.isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.EMERGENCY, RestoreCoordinator.currentMode())
    }

    @Test fun injectedWalCheckpointFailureForcesTerminalRestartWithoutRename() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source), failAt(RestoreFaultPoint.WAL_CHECKPOINT)).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED, RestoreCoordinator.current())
    }

    @Test fun injectedLiveToOldRenameFailureLeavesOriginalLiveTerminal() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source), failAt(RestoreFaultPoint.LIVE_TO_OLD_RENAME)).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED, RestoreCoordinator.current())
    }

    @Test fun injectedOldRollbackRenameFailureEntersEmergencyWithOldPreserved() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val faults = RestoreFaultInjector { point ->
            if (point == RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME ||
                point == RestoreFaultPoint.OLD_ROLLBACK_RENAME
            ) error("injected $point")
        }
        assertTrue(execute(source, metadata(source), faults).isFailure)
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
        assertTrue(databaseDir.listFiles().orEmpty().any { it.name.endsWith(".old.db") })
    }

    @Test fun injectedSafetyPromotionFailureEntersEmergencyWithSafetyPreserved() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val faults = RestoreFaultInjector { point ->
            when (point) {
                RestoreFaultPoint.INSTALLED_VALIDATION -> {
                    databaseDir.listFiles().orEmpty().single { it.name.endsWith(".old.db") }
                        .writeText("corrupt old")
                    error("force safety fallback")
                }
                RestoreFaultPoint.SAFETY_FALLBACK_PROMOTION -> error("injected safety promotion")
                else -> Unit
            }
        }
        assertTrue(execute(source, metadata(source), faults).isFailure)
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
        assertTrue(databaseDir.listFiles().orEmpty().any { it.name.endsWith(".safety.db") })
    }

    private data class RestoreOutcome(
        val state: RestoreTransaction.State,
        val old: File,
    )

    private suspend fun successfulRestore(): RestoreOutcome {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val result = execute(source, metadata(source))
        assertTrue(result.exceptionOrNull()?.stackTraceToString(), result.isSuccess)
        val state = readState()
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, state.phase)
        return RestoreOutcome(state, File(databaseDir, state.oldName))
    }

    private suspend fun restoreWithFailure(point: RestoreFaultPoint): Result<Unit> {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        return execute(source, metadata(source), failAt(point))
    }

    private fun failAt(point: RestoreFaultPoint): RestoreFaultInjector {
        var fired = false
        return RestoreFaultInjector { actual ->
            if (!fired && actual == point) {
                fired = true
                error("Injected $point")
            }
        }
    }
}
