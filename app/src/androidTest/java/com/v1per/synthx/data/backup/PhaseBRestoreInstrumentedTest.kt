package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.system.Os
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import com.v1per.synthx.data.scanner.FolderScanner
import com.v1per.synthx.data.scanner.ScanResult
import com.v1per.synthx.playback.QueuePersistence
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhaseBRestoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseDir: File get() = requireNotNull(context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile)
    private val registeredDatabaseNames = mutableSetOf<String>()

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SynthXDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Before
    fun setUp() {
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreStartup.resetForTest()
        RestoreCoordinator.resetForTest()
        cleanOwnedFiles()
    }

    @After
    fun tearDown() {
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        if (RestoreCoordinator.activeOperationCountForTest() == 0) RestoreCoordinator.resetForTest()
        registeredDatabaseNames.forEach(context::deleteDatabase)
        cleanOwnedFiles()
    }

    @Test fun trustedPhaseASourceAccepted() {
        val source = newTrustedSource("accepted")
        val metadata = metadata(source)
        assertEquals(RestoreEligibility.TRUSTED_CURRENT, RestoreEligibility.assess(metadata, source.name))
        assertEquals(SynthXDatabase.VERSION, BackupSnapshotValidator.validate(source, false).schema.userVersion)
    }

    @Test fun selectedSafDatabaseOpenedExactlyOnce() {
        val source = newTrustedSource("read-once")
        val stage = ownedFile("phaseb-read-once-stage.db")
        val opens = AtomicInteger()
        val digest = RestoreTransaction.copySafOnce(
            context,
            Uri.fromFile(source).toString(),
            stage,
            sourceOpener = RestoreSourceOpener { sourceContext, uri ->
                opens.incrementAndGet()
                sourceContext.contentResolver.openInputStream(Uri.parse(uri))
            },
        )
        assertEquals(1, opens.get())
        assertEquals(BackupHashing.hash(source), digest)
    }

    @Test fun sourceShaMismatchAbortsBeforeClose() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val bad = metadata(source).copy(sha256 = "0".repeat(64))
        assertTrue(execute(source, bad).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun corruptDatabaseRejected() {
        val corrupt = ownedFile(validBackupName()).apply { writeText("not sqlite") }
        expectFailure { BackupSnapshotValidator.validate(corrupt, true) }
    }

    @Test fun foreignSQLiteRejected() {
        val foreign = ownedFile(validBackupName())
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { it.execSQL("CREATE TABLE foreign_data(id INTEGER)") }
        expectFailure { BackupSnapshotValidator.validate(foreign, true) }
    }

    @Test fun newerSchemaRejectedBeforeSourceOpen() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val opens = AtomicInteger()
        val result = RestoreTransaction.execute(
            context,
            backupInfo(source),
            metadata(source).copy(dbVersion = SynthXDatabase.VERSION + 1),
            sourceOpener = RestoreSourceOpener { _, _ -> opens.incrementAndGet(); null },
        )
        assertTrue(result.isFailure)
        assertEquals(0, opens.get())
        assertEquals("state-a", readMarker(liveFile()))
    }

    @Test fun legacyBackupRejected() {
        assertEquals(RestoreEligibility.LEGACY_OR_MALFORMED, RestoreEligibility.assess(null, validBackupName()))
    }

    @Test fun olderTrustedSchemaMigratesOffLive() {
        createLive("state-a")
        val olderName = "phaseb-v7-${UUID.randomUUID()}.db"
        registeredDatabaseNames += olderName
        migrationHelper.createDatabase(olderName, 7).close()
        val older = context.getDatabasePath(olderName)
        assertEquals(7, BackupSnapshotValidator.validate(older, true).schema.userVersion)
        val isolated = SynthXDatabase.createStandalone(context, olderName)
        try { isolated.openHelper.writableDatabase } finally { isolated.close() }
        assertEquals("state-a", readMarker(liveFile()))
    }

    @Test fun migratedCandidateValidatesCurrentSchema() {
        val olderName = "phaseb-v7-${UUID.randomUUID()}.db"
        registeredDatabaseNames += olderName
        migrationHelper.createDatabase(olderName, 7).close()
        val isolated = SynthXDatabase.createStandalone(context, olderName)
        try { isolated.openHelper.writableDatabase } finally { isolated.close() }
        val result = BackupSnapshotValidator.validate(context.getDatabasePath(olderName), false)
        assertEquals(SynthXDatabase.VERSION, result.schema.userVersion)
    }

    @Test fun freeSpaceFailureLeavesLiveUntouched() {
        createLive("state-a")
        expectFailure {
            RestoreTransaction.ensureSpace(databaseDir, liveFile().length(), 4096, availableBytes = 0)
        }
        assertEquals("state-a", readMarker(liveFile()))
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
        room.playlistDao().insertGroup(group("wal-committed"))
        val wal = File(liveFile().path + "-wal")
        assertTrue(wal.isFile && wal.length() > 0)
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source)).isSuccess)
        val old = databaseDir.listFiles().orEmpty().single { it.name.endsWith(".old.db") }
        assertEquals("wal-committed", readMarker(old))
    }

    @Test fun fullCheckpointResultColumnsValidated() {
        val file = newTrustedSource("checkpoint")
        val result = RestoreTransaction.checkpointFileForTest(file)
        assertEquals(0, result.busy)
        assertTrue(result.provesStandaloneMain())
        assertTrue(result.logFrames == result.checkpointedFrames || result.logFrames == -1)
    }

    @Test fun nonWalModeHandledWithDocumentedAccounting() {
        val file = newTrustedSource("non-wal")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor -> assertTrue(cursor.moveToFirst()) }
        }
        val result = RestoreTransaction.checkpointFileForTest(file)
        assertTrue(result.provesStandaloneMain())
        assertTrue(
            (result.logFrames == -1 && result.checkpointedFrames == -1) ||
                result.logFrames == result.checkpointedFrames,
        )
    }

    @Test fun staleLiveBasenameWalCannotAttachToCandidate() = runBlocking {
        createLive("state-a")
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").close()
        room.playlistDao().insertGroup(group("old-wal"))
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
        val queue = QueuePersistence(repository, null)
        RestoreCoordinator.markClosed()
        expectSuspendFailure { repository.isInAnyPlaylist("missing") }
        queue.saveQueue(emptyList())
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
        expectFailure {
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

    @Test fun injectedStatePersistenceFailureLeavesLiveNormal() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        assertTrue(execute(source, metadata(source), failAt(RestoreFaultPoint.STATE_PERSISTENCE)).isFailure)
        assertEquals("state-a", readMarker(liveFile()))
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
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

    @Test fun malformedStateWithoutArtifactsGetsStableRecoveryIncidentId() {
        stateFile().writeText("bad state")
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val first = RestoreTransaction.diagnosticOperationId()
        assertTrue(first.orEmpty().startsWith("recovery-"))
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(first, RestoreTransaction.diagnosticOperationId())
    }

    @Test fun actualFolderScannerDirectAndAlreadyAdmittedEntrypointsUseOnePermit() = runBlocking {
        val name = "phaseb-scanner-${UUID.randomUUID()}.db"
        registeredDatabaseNames += name
        val database = SynthXDatabase.createStandalone(context, name)
        database.openHelper.writableDatabase
        var executions = 0
        val export = PlaylistExportManager(
            context,
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

    private data class RestoreOutcome(
        val state: RestoreTransaction.State,
        val old: File,
    )

    private data class Scenario(val state: RestoreTransaction.State)

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

    private suspend fun execute(
        source: File,
        metadata: BackupMetadata,
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    ): Result<Unit> = RestoreTransaction.execute(
        context,
        backupInfo(source),
        metadata,
        faults,
    )

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

    private fun createLive(marker: String) = createDatabaseFile(liveFile(), marker)

    private fun newTrustedSource(marker: String): File =
        ownedFile(validBackupName()).also { createDatabaseFile(it, marker) }

    private fun createDatabaseFile(destination: File, marker: String?) {
        deleteFileSet(destination)
        val builderName = "phaseb-builder-${UUID.randomUUID()}.db"
        registeredDatabaseNames += builderName
        val database = SynthXDatabase.createStandalone(context, builderName)
        try {
            database.openHelper.writableDatabase
            if (marker != null) runBlocking { database.playlistDao().insertGroup(group(marker)) }
            BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, destination)
            BackupSnapshotter.forceToStorage(destination)
        } finally {
            database.close()
            context.deleteDatabase(builderName)
        }
        BackupSnapshotValidator.validate(destination, false)
    }

    private fun group(
        marker: String,
        id: String = "marker",
        position: Int = 0,
    ) = PlaylistGroupEntity(
        id = id,
        name = marker,
        position = position,
        createdAt = 1,
    )

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

    private fun readMarker(file: File): String =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT name FROM playlist_groups WHERE id='marker'", null).use { cursor ->
                assertTrue("Missing marker in ${file.name}", cursor.moveToFirst())
                cursor.getString(0)
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

    private fun metadata(source: File): BackupMetadata {
        val digest = BackupHashing.hash(source)
        return BackupMetadata(
            timestamp = System.currentTimeMillis(),
            tier = BackupTier.Manual.name,
            dbVersion = SynthXDatabase.VERSION,
            file = source.name,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
    }

    private fun backupInfo(source: File) = BackupInfo(
        databaseUri = Uri.fromFile(source),
        metadataUri = null,
        fileName = source.name,
        tier = BackupTier.Manual,
        timestamp = System.currentTimeMillis(),
        reason = null,
        sizeBytes = source.length(),
        restoreEligible = true,
    )

    private fun readState(): RestoreTransaction.State {
        val state = RestoreTransaction.decodeStateOrNull(stateFile().readText())
        assertNotNull(state)
        return requireNotNull(state)
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

    private fun validBackupName(): String = BackupFileNames.create(
        randomId = UUID.randomUUID().toString().replace("-", "").take(8),
    ).databaseFileName

    private fun newOp(): String = UUID.randomUUID().toString().replace("-", "")
    private fun liveFile(): File = context.getDatabasePath(SynthXDatabase.DB_NAME)
    private fun stateFile(): File = File(databaseDir, "synthx.restore-state.json")
    private fun ownedFile(name: String): File = File(databaseDir, name)

    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: Exception) { }
    }

    private suspend fun expectSuspendFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: Exception) { }
    }

    private fun cleanOwnedFiles() {
        databaseDir.listFiles().orEmpty().filter { file ->
            file.name == SynthXDatabase.DB_NAME ||
                file.name.startsWith("${SynthXDatabase.DB_NAME}-") ||
                file.name.startsWith("synthx.restore-") ||
                file.name.startsWith("phaseb-") ||
                file.name.startsWith("synthx_backup_")
        }.forEach(::deleteFileSet)
    }

    private fun deleteFileSet(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
            .forEach { it.delete() }
    }
}
