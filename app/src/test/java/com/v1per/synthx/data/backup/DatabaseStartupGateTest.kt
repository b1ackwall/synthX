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

    @Test fun twentyConcurrentCallersShareInspectionAndBackupAndOpenAfterSuccess() = runTest {
        val backupStarted = CompletableDeferred<Unit>()
        val releaseBackup = CompletableDeferred<Unit>()
        var restoreAwaits = 0
        var inspections = 0
        var backups = 0
        var roomOpens = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = { restoreAwaits++ },
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(7)
            },
            createPreMigrationBackup = {
                backups++
                backupStarted.complete(Unit)
                releaseBackup.await()
            },
        )

        val callers = List(20) {
            async {
                controller.awaitReady()
                roomOpens++
            }
        }
        backupStarted.await()
        repeat(20) { yield() }

        assertEquals(1, restoreAwaits)
        assertEquals(1, inspections)
        assertEquals(1, backups)
        assertEquals(0, roomOpens)
        assertFalse(controller.isReady())

        releaseBackup.complete(Unit)
        callers.awaitAll()
        assertEquals(20, roomOpens)
        assertTrue(controller.isReady())
        controller.requireReady()
    }

    @Test fun backupFailureKeepsGateClosedAndNextCallRetries() = runTest {
        var inspections = 0
        var backupAttempts = 0
        var roomOpens = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(7)
            },
            createPreMigrationBackup = {
                backupAttempts++
                if (backupAttempts == 1) error("injected publication failure")
            },
        )

        assertFailsWith<IllegalStateException> {
            controller.awaitReady()
            roomOpens++
        }
        assertFalse(controller.isReady())
        assertFailsWith<IllegalStateException> { controller.requireReady() }
        assertEquals(0, roomOpens)
        assertEquals(1, backupAttempts)
        assertEquals(1, inspections)

        controller.awaitReady()
        roomOpens++
        assertTrue(controller.isReady())
        assertEquals(1, roomOpens)
        assertEquals(2, backupAttempts)
        assertEquals(2, inspections)
    }

    @Test fun currentV8NormalStartupAllowsRoomWithoutBackup() = runTest {
        var backups = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Present(8) },
            createPreMigrationBackup = { backups++ },
        )

        controller.awaitReady()

        assertTrue(controller.isReady())
        assertEquals(0, backups)
    }

    @Test fun newerDatabaseFailsClosedWithoutBackup() = runTest {
        var backups = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = {},
            inspectLiveVersion = { LiveDatabaseVersion.Present(9) },
            createPreMigrationBackup = { backups++ },
        )

        assertFailsWith<UnsupportedLiveDatabaseVersionException> { controller.awaitReady() }
        assertFalse(controller.isReady())
        assertEquals(0, backups)
    }

    @Test fun restoreNormalAlwaysPrecedesInspectionAndBackup() = runTest {
        val order = mutableListOf<String>()
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = { order += "restore" },
            inspectLiveVersion = {
                order += "inspect"
                LiveDatabaseVersion.Present(7)
            },
            createPreMigrationBackup = { order += "backup" },
        )

        controller.awaitReady()

        assertEquals(listOf("restore", "inspect", "backup"), order)
    }

    @Test fun restartRequiredRestoreStatePreventsInspectionBackupAndRoom() = runTest {
        assertRestoreBlockStopsGate("RESTART_REQUIRED")
    }

    @Test fun emergencyRestoreStatePreventsInspectionBackupAndRoom() = runTest {
        assertRestoreBlockStopsGate("EMERGENCY")
    }

    private suspend fun assertRestoreBlockStopsGate(state: String) {
        var inspections = 0
        var backups = 0
        var roomOpens = 0
        val controller = DatabaseStartupController(
            currentVersion = 8,
            awaitRestoreNormal = { error("restore state $state blocks normal startup") },
            inspectLiveVersion = {
                inspections++
                LiveDatabaseVersion.Present(7)
            },
            createPreMigrationBackup = { backups++ },
        )

        assertFailsWith<IllegalStateException> {
            controller.awaitReady()
            roomOpens++
        }
        assertFalse(controller.isReady())
        assertEquals(0, inspections)
        assertEquals(0, backups)
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
