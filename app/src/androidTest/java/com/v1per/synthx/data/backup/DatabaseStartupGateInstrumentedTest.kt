package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.data.repository.SongRepositoryImpl
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseStartupGateInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val live: File get() = context.getDatabasePath(SynthXDatabase.DB_NAME)

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
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        RestoreStartup.resetForTest()
        RestoreCoordinator.resetForTest()
        deleteLiveSet()
        DatabaseStartupGate.resetForTest(context)
    }

    @After
    fun tearDown() {
        SynthXDatabase.closeInstance()
        SongRepositoryImpl.resetForTest()
        if (RestoreCoordinator.activeOperationCountForTest() == 0) {
            RestoreCoordinator.resetForTest()
        }
        deleteLiveSet()
        DatabaseStartupGate.resetForTest(context)
    }

    @Test
    fun directLiveRoomFactoryFailsClosedBeforeGateAndWorksAfterReady() = runBlocking {
        val backups = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            createPreMigrationBackup = { backups.incrementAndGet() },
        )
        DatabaseStartupGate.installForTest(controller)

        expectFailure { SynthXDatabase.getInstance(context) }
        assertFalse(live.exists())

        controller.awaitReady()
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase

        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
        assertEquals(0, backups.get())
    }

    @Test
    fun workerFirstOlderDatabaseCannotMigrateUntilSharedBackupSucceeds() = runBlocking {
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, 7).close()
        assertEquals(LiveDatabaseVersion.Present(7), LiveDatabaseVersionReader.read(live))

        val backupStarted = CompletableDeferred<Unit>()
        val releaseBackup = CompletableDeferred<Unit>()
        val inspections = AtomicInteger()
        val backups = AtomicInteger()
        val order = mutableListOf<String>()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = {
                inspections.incrementAndGet()
                order += "inspect"
                LiveDatabaseVersionReader.read(live)
            },
            createPreMigrationBackup = { fromVersion ->
                assertEquals(7, fromVersion)
                backups.incrementAndGet()
                order += "backup-start"
                backupStarted.complete(Unit)
                releaseBackup.await()
                order += "backup-success"
            },
        )
        DatabaseStartupGate.installForTest(controller)

        val worker = async {
            WorkerDatabaseAccess.run {
                val repository = SongRepositoryImpl.create(context)
                repository.getQueueSongIds()
                order += "room-open"
            }
        }
        backupStarted.await()

        assertFalse(worker.isCompleted)
        assertFalse(controller.isReady())
        assertEquals(1, inspections.get())
        assertEquals(1, backups.get())
        expectFailure { SynthXDatabase.getInstance(context) }
        assertEquals(
            "The live schema changed before the pre-migration backup succeeded",
            LiveDatabaseVersion.Present(7),
            LiveDatabaseVersionReader.read(live),
        )

        releaseBackup.complete(Unit)
        worker.await()

        assertTrue(controller.isReady())
        assertEquals(
            listOf("inspect", "backup-start", "backup-success", "room-open"),
            order,
        )
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
    }

    @Test
    fun failedRequiredBackupLeavesOlderFileUnmigratedAndRetryable() = runBlocking {
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, 7).close()
        val backupAttempts = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            createPreMigrationBackup = {
                if (backupAttempts.incrementAndGet() == 1) {
                    error("injected pre-migration backup failure")
                }
            },
        )
        DatabaseStartupGate.installForTest(controller)

        expectSuspendFailure { controller.awaitReady() }
        assertFalse(controller.isReady())
        expectFailure { SynthXDatabase.getInstance(context) }
        assertEquals(LiveDatabaseVersion.Present(7), LiveDatabaseVersionReader.read(live))
        assertEquals(1, backupAttempts.get())

        controller.awaitReady()
        assertTrue(controller.isReady())
        assertEquals(2, backupAttempts.get())
        SynthXDatabase.getInstance(context).openHelper.writableDatabase
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
    }

    @Test
    fun currentV8WorkerPathOpensNormallyWithoutMigrationBackup() = runBlocking {
        DatabaseStartupGate.markReadyForTest()
        SynthXDatabase.getInstance(context).openHelper.writableDatabase
        SynthXDatabase.closeInstance()
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )

        val backups = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            createPreMigrationBackup = { backups.incrementAndGet() },
        )
        DatabaseStartupGate.installForTest(controller)

        WorkerDatabaseAccess.run {
            SynthXDatabase.getInstance(context).openHelper.writableDatabase
        }

        assertTrue(controller.isReady())
        assertEquals(0, backups.get())
    }

    @Test
    fun rawVersionReaderObservesCommittedUserVersionInWalWithoutRoom() {
        live.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(live, null).use { database ->
            assertTrue(database.enableWriteAheadLogging())
            database.execSQL("PRAGMA user_version = 7")
            assertTrue(File(live.path + "-wal").isFile)

            assertEquals(LiveDatabaseVersion.Present(7), LiveDatabaseVersionReader.read(live))
        }
    }

    @Test
    fun restoreTerminalStatesPreventInspectionBackupAndRoom() = runBlocking {
        for (terminal in listOf("RESTART_REQUIRED", "EMERGENCY")) {
            when (terminal) {
                "RESTART_REQUIRED" -> RestoreCoordinator.markRestartRequired(false)
                "EMERGENCY" -> RestoreCoordinator.markEmergency("c1-test")
            }
            val inspections = AtomicInteger()
            val backups = AtomicInteger()
            val controller = DatabaseStartupController(
                currentVersion = SynthXDatabase.VERSION,
                awaitRestoreNormal = { RestoreStartup.requireNormal() },
                inspectLiveVersion = {
                    inspections.incrementAndGet()
                    LiveDatabaseVersion.Missing
                },
                createPreMigrationBackup = { backups.incrementAndGet() },
            )
            DatabaseStartupGate.installForTest(controller)

            expectSuspendFailure { controller.awaitReady() }
            expectFailure { SynthXDatabase.getInstance(context) }
            assertFalse(controller.isReady())
            assertEquals(0, inspections.get())
            assertEquals(0, backups.get())
            assertFalse(live.exists())
            RestoreCoordinator.resetForTest()
        }
    }

    private fun deleteLiveSet() {
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm"), File(live.path + "-journal"))
            .forEach { it.delete() }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected failure")
        } catch (_: Exception) {
            // Expected.
        }
    }

    private suspend fun expectSuspendFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected failure")
        } catch (_: Exception) {
            // Expected.
        }
    }
}
