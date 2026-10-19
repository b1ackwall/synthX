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
import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// NOTE (Part 1 rename): DatabaseStartupController's `createPreMigrationBackup` parameter became
// `performMigration` when the gate stopped requiring a successful SAF backup and started requiring
// an unconditional internal snapshot + best-effort SAF backup + the migration itself + a
// post-migration invariant check (see DatabaseStartupGate.kt's performProductionMigration). Every
// test below drives the generic controller through an injected fake for that callback — none of
// them modeled SAF specifically or asserted "no SAF folder configured" as a permanent, correct
// lockout, so no test's *intent* changed here, only the callback's name and the tests'/variables'
// wording (updated below for accuracy: "backup" -> "migration" throughout).
@RunWith(AndroidJUnit4::class)
class DatabaseStartupGateInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

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
        deleteLiveSet()
        DatabaseStartupGate.resetForTest(context)
        RestorePermitLeakCheck.assertDrainedThenReset()
    }

    @Test
    fun directLiveRoomFactoryFailsClosedBeforeGateAndWorksAfterReady() = runBlocking {
        val migrations = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            performMigration = { migrations.incrementAndGet() },
        )
        DatabaseStartupGate.installForTest(controller)

        expectFailure(IllegalStateException::class, ROOM_BLOCKED) { SynthXDatabase.getInstance(context) }
        assertFalse(live.exists())

        controller.awaitReady()
        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase

        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
        assertEquals(0, migrations.get())
    }

    @Test
    fun workerFirstOlderDatabaseCannotMigrateUntilSharedMigrationSucceeds() = runBlocking {
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, 7).close()
        assertEquals(LiveDatabaseVersion.Present(7), LiveDatabaseVersionReader.read(live))

        val migrationStarted = CompletableDeferred<Unit>()
        val releaseMigration = CompletableDeferred<Unit>()
        val inspections = AtomicInteger()
        val migrations = AtomicInteger()
        val order = mutableListOf<String>()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = {
                inspections.incrementAndGet()
                order += "inspect"
                LiveDatabaseVersionReader.read(live)
            },
            performMigration = { fromVersion ->
                assertEquals(7, fromVersion)
                migrations.incrementAndGet()
                order += "migration-start"
                migrationStarted.complete(Unit)
                releaseMigration.await()
                order += "migration-success"
            },
        )
        DatabaseStartupGate.installForTest(controller)

        val worker = async {
            WorkerDatabaseAccess.run {
                val repository = SongRepositoryImpl.create(context)
                repository.getQueueSlots()
                order += "room-open"
            }
        }
        // The worker waits inside the gate for releaseMigration; release it even when an
        // assertion fails, so the failure reports at once instead of hanging the test.
        try {
            migrationStarted.await()

            assertFalse(worker.isCompleted)
            assertFalse(controller.isReady())
            assertEquals(1, inspections.get())
            assertEquals(1, migrations.get())
            expectFailure(IllegalStateException::class, ROOM_BLOCKED) { SynthXDatabase.getInstance(context) }
            assertEquals(
                "The live schema changed before the pre-migration snapshot succeeded",
                LiveDatabaseVersion.Present(7),
                LiveDatabaseVersionReader.read(live),
            )
        } finally {
            releaseMigration.complete(Unit)
        }
        worker.await()

        assertTrue(controller.isReady())
        assertEquals(
            listOf("inspect", "migration-start", "migration-success", "room-open"),
            order,
        )
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
    }

    @Test
    fun failedRequiredMigrationLeavesOlderFileUnmigratedAndRetryable() = runBlocking {
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, 7).close()
        val migrationAttempts = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            performMigration = {
                if (migrationAttempts.incrementAndGet() == 1) {
                    error("injected pre-migration failure")
                }
            },
        )
        DatabaseStartupGate.installForTest(controller)

        expectSuspendFailure(IllegalStateException::class, "injected pre-migration failure") { controller.awaitReady() }
        assertFalse(controller.isReady())
        expectFailure(IllegalStateException::class, ROOM_BLOCKED) { SynthXDatabase.getInstance(context) }
        assertEquals(LiveDatabaseVersion.Present(7), LiveDatabaseVersionReader.read(live))
        assertEquals(1, migrationAttempts.get())

        controller.awaitReady()
        assertTrue(controller.isReady())
        assertEquals(2, migrationAttempts.get())
        SynthXDatabase.getInstance(context).openHelper.writableDatabase
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
    }

    @Test
    fun currentV8WorkerPathOpensNormallyWithoutMigration() = runBlocking {
        DatabaseStartupGate.markReadyForTest()
        SynthXDatabase.getInstance(context).openHelper.writableDatabase
        SynthXDatabase.closeInstance()
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )

        val migrations = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            performMigration = { migrations.incrementAndGet() },
        )
        DatabaseStartupGate.installForTest(controller)

        WorkerDatabaseAccess.run {
            SynthXDatabase.getInstance(context).openHelper.writableDatabase
        }

        assertTrue(controller.isReady())
        assertEquals(0, migrations.get())
    }

    @Test
    fun trulyEmptyZeroByteFileReadsAsEmptyFileNotPresentZero() {
        // A file that exists but was never touched by any
        // committed SQLite transaction (the exact state left by a first-ever database creation
        // interrupted before Room's schema-creation transaction commits) is a genuine 0-byte
        // file. SQLite opens a 0-byte file as a brand-new, schema-less database rather than
        // erroring — PRAGMA user_version reliably reads 0 and sqlite_master has zero tables, with
        // no risk of a stale/garbage value from a partially written header page. That makes the
        // table-count check a safe, layered secondary signal (only consulted when version == 0),
        // not something that needed to become the primary discriminator.
        live.parentFile?.mkdirs()
        assertTrue(live.createNewFile())
        assertEquals(0L, live.length())

        assertEquals(LiveDatabaseVersion.EmptyFile, LiveDatabaseVersionReader.read(live))
    }

    @Test
    fun interruptedFirstCreationIsClassifiedAsFreshAndNextLaunchInitializesNormally() = runBlocking {
        // Fault injection: construct the interrupted state directly (a database file that exists but has never had a schema committed to it)
        // rather than racing real process-death timing.
        live.parentFile?.mkdirs()
        assertTrue(live.createNewFile())

        val migrations = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            performMigration = { migrations.incrementAndGet() },
        )
        DatabaseStartupGate.installForTest(controller)

        // The acceptance criterion: this file is classified as fresh, not as
        // BackupBeforeMigration(0) (whose snapshot step would throw "unknown synthX database
        // version 0" on every attempt, permanently), so the very next launch reaches readiness.
        controller.awaitReady()
        assertTrue(controller.isReady())
        assertEquals(0, migrations.get())

        val room = SynthXDatabase.getInstance(context)
        room.openHelper.writableDatabase
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(live),
        )
    }

    @Test
    fun populatedFileReportingVersionZeroStillFailsClosedViaMigrationPath() = runBlocking {
        // Critical regression-safety boundary: a genuinely old/corrupted file that happens to
        // hold real tables while reporting user_version = 0 must NOT be reclassified as fresh —
        // it must still go through the ordinary (and here, rejecting) migration/snapshot path.
        migrationHelper.createDatabase(SynthXDatabase.DB_NAME, 1).close()
        SQLiteDatabase.openDatabase(
            live.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { database -> database.execSQL("PRAGMA user_version = 0") }

        assertEquals(LiveDatabaseVersion.Present(0), LiveDatabaseVersionReader.read(live))

        val migrations = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(live) },
            performMigration = { fromVersion ->
                assertEquals(0, fromVersion)
                migrations.incrementAndGet()
            },
        )
        DatabaseStartupGate.installForTest(controller)

        controller.awaitReady()
        assertEquals(1, migrations.get())
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
    fun restoreTerminalStatesPreventInspectionMigrationAndRoom() = runBlocking {
        for (terminal in listOf("RESTART_REQUIRED", "EMERGENCY")) {
            when (terminal) {
                "RESTART_REQUIRED" -> RestoreCoordinator.markRestartRequired(false)
                "EMERGENCY" -> RestoreCoordinator.markEmergency("c1-test")
            }
            val inspections = AtomicInteger()
            val migrations = AtomicInteger()
            val controller = DatabaseStartupController(
                currentVersion = SynthXDatabase.VERSION,
                awaitRestoreNormal = { RestoreStartup.requireNormal() },
                inspectLiveVersion = {
                    inspections.incrementAndGet()
                    LiveDatabaseVersion.Missing
                },
                performMigration = { migrations.incrementAndGet() },
            )
            DatabaseStartupGate.installForTest(controller)

            expectSuspendFailure(IllegalStateException::class, "Restore requires synthX to be closed and reopened") {
                controller.awaitReady()
            }
            expectFailure(IllegalStateException::class, ROOM_BLOCKED) { SynthXDatabase.getInstance(context) }
            assertFalse(controller.isReady())
            assertEquals(0, inspections.get())
            assertEquals(0, migrations.get())
            assertFalse(live.exists())
            RestoreCoordinator.resetForTest()
        }
    }

    @Test
    fun pendingMigrationRecoveryBlocksRoomEvenWhenLiveFileReadsAsCurrent() = runBlocking {
        // Simulates the real scenario this flag exists for: a migration already ran (the live
        // file is at VERSION) but failed its post-migration invariant check, so decide() alone
        // would otherwise wrongly treat the database as fine on the next attempt.
        DatabaseStartupGate.markReadyForTest()
        SynthXDatabase.getInstance(context).openHelper.writableDatabase
        SynthXDatabase.closeInstance()
        DatabaseStartupGate.resetForTest(context)

        val inspections = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = {
                inspections.incrementAndGet()
                LiveDatabaseVersionReader.read(live)
            },
            performMigration = {},
            hasPendingMigrationRecovery = { true },
        )
        DatabaseStartupGate.installForTest(controller)

        expectSuspendFailure(
            MigrationInvariantFailedException::class,
            "A previous migration failed its post-migration safety check",
        ) { controller.awaitReady() }
        assertFalse(controller.isReady())
        expectFailure(IllegalStateException::class, ROOM_BLOCKED) { SynthXDatabase.getInstance(context) }
        assertEquals(0, inspections.get())
    }

    private fun deleteLiveSet() {
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm"), File(live.path + "-journal"))
            .forEach { it.delete() }
    }

    private fun <T : Throwable> expectFailure(type: KClass<T>, messageContains: String, block: () -> Unit): T =
        expectFailureOf(type, messageContains, block)

    private suspend fun <T : Throwable> expectSuspendFailure(
        type: KClass<T>,
        messageContains: String,
        block: suspend () -> Unit,
    ): T = expectSuspendFailureOf(type, messageContains, block)

    private companion object {
        /** DatabaseStartupGate.requireReady's refusal while the gate is not ready. */
        const val ROOM_BLOCKED = "Room cannot open until restore recovery and pre-migration maintenance complete"
    }
}
