package com.v1per.synthx.data.backup

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DatabaseStartupGateTest {
    @Test fun absentDatabaseNeedsNoMigrationBackup() {
        assertIs<DatabaseVersionDecision.FreshDatabase>(
            DatabaseVersionPolicy.decide(LiveDatabaseVersion.Missing, currentVersion = 8)
        )
    }

    @Test fun currentDatabaseNeedsNoMigrationBackup() {
        assertIs<DatabaseVersionDecision.CurrentDatabase>(
            DatabaseVersionPolicy.decide(
                LiveDatabaseVersion.Present(userVersion = 8),
                currentVersion = 8,
            )
        )
    }

    @Test fun olderDatabaseRequiresMigrationBackup() {
        assertEquals(
            DatabaseVersionDecision.BackupBeforeMigration(fromVersion = 7),
            DatabaseVersionPolicy.decide(
                LiveDatabaseVersion.Present(userVersion = 7),
                currentVersion = 8,
            ),
        )
    }

    @Test fun newerDatabaseIsUnsupportedAndNeverDowngraded() {
        assertEquals(
            DatabaseVersionDecision.UnsupportedNewerDatabase(actualVersion = 9),
            DatabaseVersionPolicy.decide(
                LiveDatabaseVersion.Present(userVersion = 9),
                currentVersion = 8,
            ),
        )
    }

    @Test fun staleRememberedCurrentVersionCannotHideOlderLiveDatabase() {
        val staleRememberedVersion = 8
        assertEquals(8, staleRememberedVersion)
        val decision = DatabaseVersionPolicy.decide(
            LiveDatabaseVersion.Present(userVersion = 7),
            currentVersion = 8,
        )

        assertEquals(DatabaseVersionDecision.BackupBeforeMigration(7), decision)
    }

    @Test fun staleRememberedOldVersionCannotCauseBackupForCurrentLiveDatabase() {
        val staleRememberedVersion = 7
        val actualCurrentVersion = 8
        assertTrue(staleRememberedVersion < actualCurrentVersion)

        val decision = DatabaseVersionPolicy.decide(
            LiveDatabaseVersion.Present(userVersion = actualCurrentVersion),
            currentVersion = actualCurrentVersion,
        )
        assertIs<DatabaseVersionDecision.CurrentDatabase>(decision)
    }

    // NOTE (Part 1 rename): DatabaseStartupController's `createPreMigrationBackup` parameter
    // became `performMigration` when the gate stopped requiring a successful SAF backup and
    // started requiring an unconditional internal snapshot + best-effort SAF backup + the
    // migration itself + a post-migration invariant check (see DatabaseStartupGate.kt's
    // performProductionMigration). At this generic-controller level none of the tests below ever
    // modeled SAF specifically — they exercise the controller's single-flight/retry state machine
    // against an abstract "the thing that must succeed before Room opens" callback, so that intent
    // is unchanged; only the callback's name (and these tests' variable/test names, updated below
    // for accuracy) changed. Confirmed while doing this repair: none of these tests asserted the
    // old SAF-lockout (data 2.1) as correct/desired behavior — that bug lived entirely in
    // production wiring (BackupManager/DatabaseStartupGate.productionController) that no test here
    // ever exercised, so no test's *intent* needed to change, only its terminology.

    @Test fun twentyConcurrentCallersShareInspectionAndMigrationAndOpenAfterSuccess() = runTest {
        val migrationStarted = CompletableDeferred<Unit>()
        val releaseMigration = CompletableDeferred<Unit>()
        var restoreAwaits = 0
        var inspections = 0
        var migrations = 0
        var roomOpens = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = { restoreAwaits++ },
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(7)
            },
            performMigration = {
                migrations++
                migrationStarted.complete(Unit)
                releaseMigration.await()
            },
        )

        val callers = List(20) {
            async {
                controller.awaitReady()
                roomOpens++
            }
        }
        migrationStarted.await()
        repeat(20) { yield() }

        assertEquals(1, restoreAwaits)
        assertEquals(1, inspections)
        assertEquals(1, migrations)
        assertEquals(0, roomOpens)
        assertFalse(controller.isReady())

        releaseMigration.complete(Unit)
        callers.awaitAll()
        assertEquals(20, roomOpens)
        assertTrue(controller.isReady())
        controller.requireReady()
    }

    @Test fun migrationFailureKeepsGateClosedAndNextCallRetries() = runTest {
        var inspections = 0
        var migrationAttempts = 0
        var roomOpens = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(7)
            },
            performMigration = {
                migrationAttempts++
                if (migrationAttempts == 1) error("injected migration failure")
            },
        )

        assertFailsWith<IllegalStateException> {
            controller.awaitReady()
            roomOpens++
        }
        assertFalse(controller.isReady())
        assertFailsWith<IllegalStateException> { controller.requireReady() }
        assertEquals(0, roomOpens)
        assertEquals(1, migrationAttempts)
        assertEquals(1, inspections)

        controller.awaitReady()
        roomOpens++
        assertTrue(controller.isReady())
        assertEquals(1, roomOpens)
        assertEquals(2, migrationAttempts)
        assertEquals(2, inspections)
    }

    @Test fun currentV8NormalStartupAllowsRoomWithoutMigration() = runTest {
        var migrations = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Present(8) },
            performMigration = { migrations++ },
        )

        controller.awaitReady()

        assertTrue(controller.isReady())
        assertEquals(0, migrations)
    }

    @Test fun newerDatabaseFailsClosedWithoutMigration() = runTest {
        var migrations = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Present(9) },
            performMigration = { migrations++ },
        )

        assertFailsWith<UnsupportedLiveDatabaseVersionException> { controller.awaitReady() }
        assertFalse(controller.isReady())
        assertEquals(0, migrations)
    }

    @Test fun restoreNormalAlwaysPrecedesInspectionAndMigration() = runTest {
        val order = mutableListOf<String>()
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = { order += "restore" },
            inspectLiveVersion = {
                order += "inspect"
                LiveDatabaseVersion.Present(7)
            },
            performMigration = { order += "migration" },
        )

        controller.awaitReady()

        assertEquals(listOf("restore", "inspect", "migration"), order)
    }

    @Test fun restartRequiredRestoreStatePreventsInspectionMigrationAndRoom() = runTest {
        assertRestoreBlockStopsGate("RESTART_REQUIRED")
    }

    @Test fun emergencyRestoreStatePreventsInspectionMigrationAndRoom() = runTest {
        assertRestoreBlockStopsGate("EMERGENCY")
    }

    // --------------------------------------------------- Part 1 additions: pending recovery + onReady

    @Test fun pendingMigrationRecoveryBlocksReadinessBeforeInspectingLiveVersion() = runTest {
        var inspections = 0
        var migrations = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(8)
            },
            performMigration = { migrations++ },
            hasPendingMigrationRecovery = { true },
        )

        assertFailsWith<MigrationInvariantFailedException> { controller.awaitReady() }
        assertFalse(controller.isReady())
        // The live file may already read as "current" post-migration — the pending-recovery
        // check must short-circuit before decide() ever gets a chance to call that "fine".
        assertEquals(0, inspections)
        assertEquals(0, migrations)
    }

    @Test fun onReadyFiresExactlyOnceAfterSuccessfulMigration() = runTest {
        var onReadyCalls = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Present(7) },
            performMigration = {},
            onReady = { onReadyCalls++ },
        )

        controller.awaitReady()
        assertEquals(1, onReadyCalls)

        // A second call on an already-ready controller must not re-fire onReady.
        controller.awaitReady()
        assertEquals(1, onReadyCalls)
    }

    @Test fun onReadyNeverFiresWhenMigrationFails() = runTest {
        var onReadyCalls = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Present(7) },
            performMigration = { error("injected migration failure") },
            onReady = { onReadyCalls++ },
        )

        assertFailsWith<IllegalStateException> { controller.awaitReady() }
        assertEquals(0, onReadyCalls)
    }

    private suspend fun assertRestoreBlockStopsGate(state: String) {
        var inspections = 0
        var migrations = 0
        var roomOpens = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = { error("restore state $state blocks normal startup") },
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(7)
            },
            performMigration = { migrations++ },
        )

        assertFailsWith<IllegalStateException> {
            controller.awaitReady()
            roomOpens++
        }
        assertFalse(controller.isReady())
        assertEquals(0, inspections)
        assertEquals(0, migrations)
        assertEquals(0, roomOpens)
    }
}

class DatabaseStartupProductionOrderingTest {
    @Test fun mainActivityAwaitsGateBeforeInstallingDatabaseConsumingContent() {
        val source = productionFile("com/v1per/synthx/MainActivity.kt").readText()
        val await = source.indexOf("DatabaseStartupGate.awaitReady()")
        val content = source.indexOf("setContent", startIndex = await)
        val scaffold = source.indexOf("MainScaffold()", startIndex = content)

        assertTrue(await >= 0, "MainActivity must await the process database gate")
        assertTrue(content > await, "setContent must follow database-gate resolution")
        assertTrue(scaffold > content, "DB-consuming MainScaffold must be installed only afterward")
        assertFalse(source.contains("backupBeforeMigration"))
        assertFalse(source.contains("lastKnownDbVersion"))
    }

    @Test fun productionMigrationBackupSelectsRawPreRoomSnapshotPath() {
        val manager = productionFile("com/v1per/synthx/data/backup/BackupManager.kt").readText()
        val snapshotter = productionFile("com/v1per/synthx/data/backup/BackupSnapshot.kt").readText()

        val entry = manager.substringAfter("createPreMigrationBackup")
            .substringBefore("private suspend fun createBackupLocked")
        assertTrue(entry.contains("BackupReason.MIGRATION"))
        assertTrue(entry.contains("cancelActiveScan = false"))
        assertTrue(manager.contains("BackupSnapshotter.createForMigration(sourceFile, snapshot)"))
        assertTrue(snapshotter.contains("SQLiteDatabase.openDatabase"))
        assertFalse(
            snapshotter.substringAfter("fun createForMigration")
                .substringBefore("fun forceToStorage")
                .contains("SynthXDatabase.getInstance")
        )
    }

    @Test fun productionSafetyDecisionHasNoRememberedVersionWriter() {
        val productionRoot = productionDirectory("com/v1per/synthx")
        val sources = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(sources.contains("lastKnownDbVersion"))
        assertFalse(sources.contains("KEY_LAST_KNOWN_DB_VERSION"))
    }

    private fun productionFile(relative: String): File {
        val candidates = listOf(
            File("src/main/java", relative),
            File("app/src/main/java", relative),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Could not locate production source $relative")
    }

    private fun productionDirectory(relative: String): File {
        val candidates = listOf(
            File("src/main/java", relative),
            File("app/src/main/java", relative),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Could not locate production directory $relative")
    }
}
