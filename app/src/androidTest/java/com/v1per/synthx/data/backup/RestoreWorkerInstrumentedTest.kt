package com.v1per.synthx.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.v1per.synthx.data.db.PlaylistGroupEntity
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestoreWorkerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val workManager get() = WorkManager.getInstance(context)
    private val databaseDir get() = requireNotNull(context.getDatabasePath(SynthXDatabase.DB_NAME).parentFile)

    @Before fun setUp() {
        RestoreStartup.await()
        TestWorkManager.ensureInitialized(context)
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreCoordinator.resetForTest()
        runBlocking { workManager.pruneWork().result.get() }
        cleanOwnedFiles()
        DatabaseStartupGate.resetForTest(context)
    }

    @After fun tearDown() {
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        if (RestoreCoordinator.activeOperationCountForTest() == 0) RestoreCoordinator.resetForTest()
        runBlocking { workManager.cancelAllWork().result.get(); workManager.pruneWork().result.get() }
        cleanOwnedFiles()
        DatabaseStartupGate.resetForTest(context)
    }

    @Test fun uniqueRestoreUsesKeepSemanticsAndReturnsTheActualOwner() = runBlocking {
        val first = backupInfo(File(databaseDir, validBackupName()))
        val second = backupInfo(File(databaseDir, validBackupName()))
        val firstOwner = RestoreWorker.enqueue(context, first)
        val secondOwner = RestoreWorker.enqueue(context, second)
        val infos = workManager.getWorkInfosForUniqueWork(RestoreWorker.UNIQUE_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(infos.single().id, firstOwner)
        assertEquals(firstOwner, secondOwner)
    }

    @Test fun restoreRequestRoundTripsTrustedReference() {
        val source = File(databaseDir, validBackupName())
        val metadata = File(databaseDir, source.name.removeSuffix(".db") + ".json")
        val backup = backupInfo(source, metadata)
        val decoded = with(RestoreWorker) { request(backup).workSpec.input.toBackupInfo() }
        assertEquals(backup.databaseUri, decoded?.databaseUri)
        assertEquals(backup.metadataUri, decoded?.metadataUri)
        assertEquals(backup.fileName, decoded?.fileName)
    }

    @Test fun preCloseWorkerFailureLeavesLiveDatabaseUsable() = runBlocking {
        createDatabaseFile(context.getDatabasePath(SynthXDatabase.DB_NAME), "state-a")
        val backup = backupInfo(File(databaseDir, validBackupName()))
        val request = RestoreWorker.request(backup)
        workManager.enqueueUniqueWork(RestoreWorker.UNIQUE_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        assertEquals(WorkInfo.State.FAILED, awaitFinished(request.id).state)
        BackupSnapshotValidator.validate(context.getDatabasePath(SynthXDatabase.DB_NAME), false)
        Unit
    }

    @Test fun workerResolvesTerminalTransactionBeforeNewRestore() = runBlocking {
        RestoreCoordinator.markRestartRequired(false)
        val request = RestoreWorker.request(backupInfo(File(databaseDir, validBackupName())))
        workManager.enqueue(request).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        assertEquals(WorkInfo.State.FAILED, awaitFinished(request.id).state)
        assertFalse(context.getDatabasePath(SynthXDatabase.DB_NAME).exists())
    }

    @Test fun successfulWorkerPersistsGlobalRestartRequired() = runBlocking {
        createDatabaseFile(context.getDatabasePath(SynthXDatabase.DB_NAME), "state-a")
        val source = File(databaseDir, validBackupName())
        createDatabaseFile(source, "state-b")
        val metadataFile = File(databaseDir, source.name.removeSuffix(".db") + ".json")
        metadataFile.writeBytes(BackupMetadataCodec.encode(metadata(source)))
        val request = RestoreWorker.request(backupInfo(source, metadataFile))
        workManager.enqueueUniqueWork(RestoreWorker.UNIQUE_WORK_NAME, androidx.work.ExistingWorkPolicy.KEEP, request).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(request.id).state)
        assertEquals(RestoreCoordinator.Terminal.RESTART_REQUIRED, RestoreCoordinator.current())
        val state = RestoreTransaction.decodeStateOrNull(
            File(databaseDir, "synthx.restore-state.json").readText()
        )
        assertEquals(request.id.toString().replace("-", ""), state?.operationId)
        assertEquals(request.id.toString().replace("-", ""), state?.requestId)
    }

    @Test fun workerRerunDoesNotBlindlyRepairPostCloseState() = runBlocking {
        createDatabaseFile(context.getDatabasePath(SynthXDatabase.DB_NAME), "state-a")
        val source = File(databaseDir, validBackupName())
        createDatabaseFile(source, "state-b")
        val metadataFile = File(databaseDir, source.name.removeSuffix(".db") + ".json")
        metadataFile.writeBytes(BackupMetadataCodec.encode(metadata(source)))
        val first = RestoreWorker.request(backupInfo(source, metadataFile))
        workManager.enqueue(first).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitFinished(first.id).state)

        val rerun = RestoreWorker.request(backupInfo(source, metadataFile))
        workManager.enqueue(rerun).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(rerun.id)
        assertEquals(WorkInfo.State.FAILED, awaitFinished(rerun.id).state)
        assertEquals(RestoreCoordinator.Terminal.RESTART_REQUIRED, RestoreCoordinator.current())
    }

    @Test fun workerFailureOutputPreservesNestedCauseDetail() = runBlocking {
        createDatabaseFile(context.getDatabasePath(SynthXDatabase.DB_NAME), "state-a")
        val source = File(databaseDir, validBackupName()).apply { writeText("not a sqlite database") }
        val metadataFile = File(databaseDir, source.name.removeSuffix(".db") + ".json")
        metadataFile.writeBytes(BackupMetadataCodec.encode(metadata(source)))
        val request = RestoreWorker.request(backupInfo(source, metadataFile))
        workManager.enqueue(request).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)

        val finished = awaitFinished(request.id)
        assertEquals(WorkInfo.State.FAILED, finished.state)
        val detail = finished.outputData.getString(RestoreWorker.KEY_ERROR).orEmpty()
        assertTrue(detail.contains("restore failed:"))
        assertTrue(detail.contains("caused by") || detail.lines().size > 1)
    }

    private suspend fun awaitFinished(id: UUID): WorkInfo =
        workManager.getWorkInfoByIdFlow(id).first { it?.state?.isFinished == true }!!

    private fun createDatabaseFile(destination: File, marker: String) {
        deleteFileSet(destination)
        val builderName = "phaseb-worker-builder-${UUID.randomUUID()}.db"
        val database = SynthXDatabase.createStandalone(context, builderName)
        try {
            database.openHelper.writableDatabase
            runBlocking {
                database.playlistDao().insertGroup(
                    PlaylistGroupEntity("marker", marker, 0, 1),
                )
            }
            BackupSnapshotter.createFromRoom(database.openHelper.writableDatabase, destination)
            BackupSnapshotter.forceToStorage(destination)
        } finally {
            database.close()
            context.deleteDatabase(builderName)
        }
    }

    private fun metadata(source: File): BackupMetadata {
        val digest = BackupHashing.hash(source)
        return BackupMetadata(
            System.currentTimeMillis(),
            BackupTier.Manual.name,
            dbVersion = SynthXDatabase.VERSION,
            file = source.name,
            sizeBytes = digest.sizeBytes,
            sha256 = digest.sha256,
        )
    }

    private fun backupInfo(source: File, metadata: File? = null) = BackupInfo(
        Uri.fromFile(source),
        metadata?.let(Uri::fromFile),
        source.name,
        BackupTier.Manual,
        System.currentTimeMillis(),
        null,
        source.length(),
        restoreEligible = metadata != null,
    )

    private fun validBackupName() = BackupFileNames.create(
        randomId = UUID.randomUUID().toString().replace("-", "").take(8),
    ).databaseFileName

    private fun cleanOwnedFiles() {
        databaseDir.listFiles().orEmpty().filter { file ->
            file.name == SynthXDatabase.DB_NAME ||
                file.name.startsWith("${SynthXDatabase.DB_NAME}-") ||
                file.name.startsWith("synthx.restore-") ||
                file.name.startsWith("phaseb-worker-") ||
                file.name.startsWith("synthx_backup_")
        }.forEach(::deleteFileSet)
    }

    private fun deleteFileSet(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
            .forEach { it.delete() }
    }
}
