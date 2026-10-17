package com.v1per.synthx.data.backup

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B restore: source admission and validation, i.e. everything that decides whether a
 * selected backup is eligible before the destructive transaction begins.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreAdmissionInstrumentedTest : PhaseBRestoreTestBase() {

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
        expectFailure(BackupSnapshotException::class, "Local snapshot validation failed") {
            BackupSnapshotValidator.validate(corrupt, true)
        }
    }

    @Test fun foreignSQLiteRejected() {
        val foreign = ownedFile(validBackupName())
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use { it.execSQL("CREATE TABLE foreign_data(id INTEGER)") }
        expectFailure(BackupSnapshotException::class, "Snapshot uses unknown synthX database version 0") {
            BackupSnapshotValidator.validate(foreign, true)
        }
    }

    @Test fun newerSchemaRejectedBeforeSourceOpen() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val opens = AtomicInteger()
        val result = RestoreTransaction.execute(
            context,
            backupInfo(source),
            metadata(source).copy(dbVersion = SynthXDatabase.VERSION + 1),
            UUID.randomUUID().toString(),
            sourceOpener = RestoreSourceOpener { _, _ -> opens.incrementAndGet(); null },
        )
        assertTrue(result.isFailure)
        assertEquals(0, opens.get())
        assertEquals("state-a", readMarker(liveFile()))
    }

    @Test fun storagePreflightRunsBeforeSafSourceIsOpened() = runBlocking {
        createLive("state-a")
        val source = newTrustedSource("state-b")
        val opens = AtomicInteger()
        val result = RestoreTransaction.execute(
            context,
            backupInfo(source),
            metadata(source),
            UUID.randomUUID().toString(),
            faults = RestoreFaultInjector { point ->
                if (point == RestoreFaultPoint.FREE_SPACE_PREFLIGHT) error("no space")
            },
            sourceOpener = RestoreSourceOpener { _, _ -> opens.incrementAndGet(); source.inputStream() },
        )
        assertTrue(result.isFailure)
        assertEquals(0, opens.get())
        assertEquals("state-a", readMarker(liveFile()))
    }

    @Test fun growingSourceIsAbortedBeforeItCanBeFullyCopied() {
        val destination = ownedFile("phaseb-growing-source.db")
        var bytesRead = 0
        val source = object : InputStream() {
            private val bytes = ByteArray(1_000) { 1 }
            private var position = 0
            override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt().also { bytesRead++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position == bytes.size) return -1
                val count = minOf(4, length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                bytesRead += count
                return count
            }
        }
        expectFailure(IllegalStateException::class, "Selected backup exceeds its declared size") {
            RestoreTransaction.copySafOnce(
                context,
                "content://growing",
                destination,
                sourceOpener = RestoreSourceOpener { _, _ -> source },
                expectedSizeBytes = 10,
            )
        }
        assertTrue(bytesRead < 1_000)
        assertTrue(destination.length() <= 10)
    }

    /**
     * P7-T7/R7-F17: the cooperative `isCancelled` poll copySafOnce added so an in-flight
     * preview/restore copy can actually be interrupted, not just have its overlay hidden.
     * Polls true only after several iterations to prove the loop genuinely stops mid-stream
     * — not merely refusing to start.
     */
    @Test fun copySafOnceStopsPartwayThroughWhenCancelled() {
        val destination = ownedFile("phaseb-cancelled-copy.db")
        var bytesRead = 0
        val totalSize = 1_000_000
        val source = object : InputStream() {
            private val bytes = ByteArray(totalSize) { 1 }
            private var position = 0
            override fun read(): Int = if (position == bytes.size) -1 else bytes[position++].toInt().also { bytesRead++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position == bytes.size) return -1
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                bytesRead += count
                return count
            }
        }
        var polls = 0
        expectFailure(CancellationException::class, "Backup copy cancelled") {
            RestoreTransaction.copySafOnce(
                context,
                "content://cancel-me",
                destination,
                sourceOpener = RestoreSourceOpener { _, _ -> source },
                isCancelled = { polls++; polls > 3 },
            )
        }
        assertTrue(
            "Expected the copy to stop well before the full $totalSize-byte source " +
                "was consumed, but read $bytesRead bytes",
            bytesRead < totalSize,
        )
    }

    @Test fun copySafOnceIgnoresCancellationByDefault() {
        // The default isCancelled always returns false — every existing non-coroutine call
        // site above (and PhaseBRestoreTransactionInstrumentedTest's) must keep working
        // unchanged, so this is a regression guard on the new parameter's default.
        val source = newTrustedSource("unaffected-by-default")
        val destination = ownedFile("phaseb-default-not-cancelled.db")
        val digest = RestoreTransaction.copySafOnce(context, Uri.fromFile(source).toString(), destination)
        assertEquals(BackupHashing.hash(source), digest)
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
        expectFailure(IllegalStateException::class, "Not enough internal storage to restore safely") {
            RestoreTransaction.ensureSpace(databaseDir, liveFile().length(), 4096, availableBytes = 0)
        }
        assertEquals("state-a", readMarker(liveFile()))
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
}
