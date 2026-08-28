package com.v1per.synthx.data.backup

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B restore: crash recovery from every persisted journal phase, plus the COMPLETE-phase
 * cold-start behaviour driven through the real [RestoreStartup] path.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreRecoveryInstrumentedTest : PhaseBRestoreTestBase() {

    @Test fun liveMoveIntentBeforeRenameRecoversOriginalLive() {
        val scenario = scenario(RestoreTransaction.Phase.LIVE_MOVE_INTENT, live = "original")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
        assertFalse(File(databaseDir, scenario.state.candidateName).exists())
    }

    @Test fun liveMovedRecoveryPromotesOld() {
        scenario(RestoreTransaction.Phase.LIVE_MOVED, old = "original")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
    }

    @Test fun installIntentPreInstallRecoversOld() {
        scenario(RestoreTransaction.Phase.INSTALL_INTENT, old = "original")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
    }

    @Test fun installIntentPostInstallRecognizesExactCandidate() {
        val scenario = scenario(RestoreTransaction.Phase.INSTALL_INTENT, old = "original", installCandidate = true)
        assertEquals(RestoreRecovery.RESTART_REQUIRED, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("candidate", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, readState().phase)
        assertTrue(File(databaseDir, scenario.state.oldName).isFile)
    }

    @Test fun candidateInstalledRecoveryRequiresRestart() {
        scenario(RestoreTransaction.Phase.CANDIDATE_INSTALLED, old = "original", installCandidate = true)
        assertEquals(RestoreRecovery.RESTART_REQUIRED, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, readState().phase)
    }

    @Test fun invalidInstalledCandidateRollsBackOld() {
        val scenario = scenario(RestoreTransaction.Phase.CANDIDATE_INSTALLED, old = "original")
        liveFile().writeText("invalid installed")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
        assertFalse(File(databaseDir, scenario.state.oldName).exists())
    }

    @Test fun postValidatedColdStartBecomesCompleteAndCleansSafety() {
        val scenario = scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertFalse(File(databaseDir, scenario.state.oldName).exists())
        assertFalse(File(databaseDir, scenario.state.safetyName).exists())
    }

    @Test fun completeValidUnchangedLiveWithMissingArtifactsStartsNormal() {
        val completed = completeInstalledScenario()
        assertEquals(completed.state.candidateSize, liveFile().length())
        assertEquals(completed.state.candidateSha256, BackupHashing.hash(liveFile()).sha256)
        assertOperationArtifactsAbsent(completed.state)

        assertEquals(RestoreRecovery.NORMAL, RestoreStartup.recoverNowForTest(context))
        assertEquals(RestoreCoordinator.Terminal.NORMAL, RestoreCoordinator.current())
        assertEquals("candidate", readMarker(liveFile()))
    }

    @Test fun completeEvolvedLiveWithDifferentShaStartsNormallyThroughRealStartup() = runBlocking {
        val completed = completeInstalledScenario()
        val historicalSha = completed.state.candidateSha256
        assertOperationArtifactsAbsent(completed.state)

        val room = SynthXDatabase.getInstance(context)
        room.playlistDao().insertGroup(group("post-complete-data", id = "post-complete"))
        checkpointRoom(room)
        SynthXDatabase.closeInstance()

        assertNotEquals(historicalSha, BackupHashing.hash(liveFile()).sha256)
        assertEquals(RestoreRecovery.NORMAL, RestoreStartup.recoverNowForTest(context))
        assertEquals(RestoreCoordinator.Terminal.NORMAL, RestoreCoordinator.current())

        val reopened = SynthXDatabase.getInstance(context)
        reopened.playlistDao().renameGroup("post-complete", "post-complete-still-present")
        SynthXDatabase.closeInstance()
        assertEquals("post-complete-still-present", readGroupName(liveFile(), "post-complete"))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun completeEvolvedLiveWithDifferentSizeStartsNormal() = runBlocking {
        val completed = completeInstalledScenario()
        val historicalSize = completed.state.candidateSize

        val room = SynthXDatabase.getInstance(context)
        repeat(160) { index ->
            room.playlistDao().insertGroup(
                group(
                    marker = "expanded-$index-${"x".repeat(8192)}",
                    id = "expanded-$index",
                    position = index + 1,
                ),
            )
        }
        checkpointRoom(room)
        SynthXDatabase.closeInstance()

        assertNotEquals(historicalSize, liveFile().length())
        assertEquals(RestoreRecovery.NORMAL, RestoreStartup.recoverNowForTest(context))
        val reopened = SynthXDatabase.getInstance(context)
        reopened.playlistDao().renameGroup("expanded-159", "expanded-data-still-present")
        SynthXDatabase.closeInstance()
        assertEquals("expanded-data-still-present", readGroupName(liveFile(), "expanded-159"))
    }

    @Test fun completeAcceptsOrdinaryLiveWalAndShmWithoutRemovingThem() = runBlocking {
        completeInstalledScenario()
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").close()
        room.playlistDao().insertGroup(group("wal-data", id = "post-complete-wal"))
        val wal = File(liveFile().path + "-wal")
        val shm = File(liveFile().path + "-shm")
        assertTrue(wal.isFile && wal.length() > 0)
        assertTrue(shm.isFile)

        assertEquals(RestoreRecovery.NORMAL, RestoreStartup.recoverNowForTest(context))
        assertEquals(RestoreCoordinator.Terminal.NORMAL, RestoreCoordinator.current())
        assertTrue(wal.isFile)
        assertTrue(shm.isFile)
        room.playlistDao().renameGroup("post-complete-wal", "wal-data-still-present")
        assertEquals("wal-data-still-present", readGroupName(liveFile(), "post-complete-wal"))
    }

    @Test fun completeJournalMayRemainForeverAcrossRepeatedStartups() {
        completeInstalledScenario()
        assertTrue(stateFile().isFile)

        repeat(100) {
            RestoreCoordinator.resetForTest()
            assertEquals(RestoreRecovery.NORMAL, RestoreStartup.recoverNowForTest(context))
            assertEquals(RestoreCoordinator.Terminal.NORMAL, RestoreCoordinator.current())
            assertTrue("COMPLETE journal disappeared on startup ${it + 1}", stateFile().isFile)
        }
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun completeStructurallyCorruptLiveFailsClosedWithoutRollback() {
        val completed = completeInstalledScenario()
        liveFile().writeText("genuinely corrupt current live")
        assertOperationArtifactsAbsent(completed.state)

        assertEquals(RestoreRecovery.EMERGENCY, RestoreStartup.recoverNowForTest(context))
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
        expectFailure { SynthXDatabase.getInstance(context) }
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertOperationArtifactsAbsent(completed.state)
    }

    @Test fun postValidatedValidIdentityMismatchStillRollsBack() {
        val pending = scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            old = "pre-restore",
            installCandidate = true,
        )
        updateMarker(liveFile(), "changed-before-first-cold-confirmation")
        assertNotEquals(pending.state.candidateSha256, BackupHashing.hash(liveFile()).sha256)

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("pre-restore", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
    }

    @Test fun candidateInstalledValidIdentityMismatchStillRollsBack() {
        val pending = scenario(
            RestoreTransaction.Phase.CANDIDATE_INSTALLED,
            old = "pre-restore",
            installCandidate = true,
        )
        updateMarker(liveFile(), "changed-before-post-validation")
        assertNotEquals(pending.state.candidateSha256, BackupHashing.hash(liveFile()).sha256)

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("pre-restore", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
    }

    @Test fun rollbackIntentContinuesIdempotently() {
        scenario(
            RestoreTransaction.Phase.ROLLBACK_INTENT,
            old = "original",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
        )
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun rolledBackLiveIsValidatedBeforeComplete() {
        scenario(
            RestoreTransaction.Phase.ROLLED_BACK,
            live = "original",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
        )
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun malformedStateWithValidOldRecoversOld() {
        val op = newOp()
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).old), "old")
        stateFile().writeText("{")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("old", readMarker(liveFile()))
    }

    @Test fun malformedStateWithInvalidOldUsesSafety() {
        val op = newOp()
        File(databaseDir, RestoreArtifactNames(op).old).writeText("bad")
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).safety), "safety")
        stateFile().writeText("truncated")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("safety", readMarker(liveFile()))
    }

    @Test fun missingStateWithValidOldRecoversOld() {
        val op = newOp()
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).old), "old")
        assertFalse(stateFile().exists())
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("old", readMarker(liveFile()))
    }

    @Test fun noValidRecoveryCopyEntersEmergencyWithStableId() {
        val op = newOp()
        File(databaseDir, RestoreArtifactNames(op).old).writeText("bad")
        stateFile().writeText("bad state")
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val first = RestoreTransaction.diagnosticOperationId()
        assertEquals(op, first)
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(first, RestoreTransaction.diagnosticOperationId())
    }

    @Test fun malformedStateWithoutArtifactsGetsStableRecoveryIncidentId() {
        stateFile().writeText("bad state")
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val first = RestoreTransaction.diagnosticOperationId()
        assertTrue(first.orEmpty().startsWith("recovery-"))
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(first, RestoreTransaction.diagnosticOperationId())
    }

    private data class Scenario(val state: RestoreTransaction.State)

    private fun completeInstalledScenario(): Scenario {
        scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            old = "pre-restore",
            installCandidate = true,
        )
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val completed = Scenario(readState())
        assertEquals(RestoreTransaction.Phase.COMPLETE, completed.state.phase)
        assertOperationArtifactsAbsent(completed.state)
        return completed
    }

    private fun scenario(
        phase: RestoreTransaction.Phase,
        live: String? = null,
        old: String? = null,
        installCandidate: Boolean = false,
        outcome: RestoreTransaction.Outcome = RestoreTransaction.Outcome.INSTALLED,
    ): Scenario {
        val op = newOp()
        val names = RestoreArtifactNames(op)
        val candidate = File(databaseDir, names.candidate)
        val safety = File(databaseDir, names.safety)
        val oldFile = File(databaseDir, names.old)
        createDatabaseFile(candidate, "candidate")
        createDatabaseFile(safety, "safety")
        if (old != null) createDatabaseFile(oldFile, old)
        if (live != null) createDatabaseFile(liveFile(), live)
        val candidateDigest = BackupHashing.hash(candidate)
        val safetyDigest = BackupHashing.hash(safety)
        val oldDigest = oldFile.takeIf(File::isFile)?.let(BackupHashing::hash)
        val state = RestoreTransaction.State(
            operationId = op,
            sourceName = validBackupName(),
            sourceUri = "content://phase-b/source",
            candidateName = names.candidate,
            oldName = names.old,
            safetyName = names.safety,
            failedName = names.failed,
            candidateSha256 = candidateDigest.sha256,
            candidateSize = candidateDigest.sizeBytes,
            candidateVersion = SynthXDatabase.VERSION,
            safetySha256 = safetyDigest.sha256,
            safetySize = safetyDigest.sizeBytes,
            phase = phase,
            createdAt = 1,
            oldSha256 = oldDigest?.sha256,
            oldSize = oldDigest?.sizeBytes,
            oldVersion = oldDigest?.let { SynthXDatabase.VERSION },
            outcome = outcome,
        )
        RestoreTransaction.writeStateForTest(databaseDir, state)
        if (installCandidate) Os.rename(candidate.path, liveFile().path)
        return Scenario(state)
    }

    private fun checkpointRoom(room: SynthXDatabase) {
        room.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(cursor.getInt(1), cursor.getInt(2))
        }
    }

    private fun updateMarker(file: File, value: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL(
                "UPDATE playlist_groups SET name=? WHERE id='marker'",
                arrayOf(value),
            )
        }
        assertEquals(value, readMarker(file))
        BackupSnapshotValidator.validate(file, false)
    }

    private fun assertOperationArtifactsAbsent(state: RestoreTransaction.State) {
        listOf(state.candidateName, state.oldName, state.safetyName).forEach { name ->
            assertFalse("Unexpected completed-restore artifact $name", File(databaseDir, name).exists())
        }
    }

    private fun readGroupName(file: File, id: String): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery(
                "SELECT name FROM playlist_groups WHERE id=?",
                arrayOf(id),
            ).use { cursor ->
                assertTrue("Missing group $id in ${file.name}", cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    private fun newOp(): String = UUID.randomUUID().toString().replace("-", "")
}
