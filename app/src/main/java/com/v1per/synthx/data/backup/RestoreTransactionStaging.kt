package com.v1per.synthx.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

// RestoreTransaction's candidate staging: SAF copy, free-space preflight, migration and standalone proof.

internal fun RestoreTransaction.proveCandidateStandalone(candidate: File) {
    val result = SQLiteDatabase.openDatabase(
        candidate.path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    ).use { database ->
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use(::readCheckpointResult)
    }
    check(result.provesStandaloneMain()) {
        "Candidate WAL checkpoint did not prove a standalone main database: $result"
    }
    detachSidecars(candidate, File(candidate.path + ".checkpointed"))
}

internal fun RestoreTransaction.checkpointFileForTest(file: File): WalCheckpointResult =
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use(::readCheckpointResult)
    }

internal fun readCheckpointResult(cursor: android.database.Cursor): WalCheckpointResult {
    check(cursor.columnCount >= 3 && cursor.moveToFirst()) {
        "SQLite did not return a complete WAL checkpoint result"
    }
    return WalCheckpointResult(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
}

internal fun RestoreTransaction.migrateCandidate(context: Context, candidateName: String) {
    val database = SynthXDatabase.createStandalone(context, candidateName)
    try {
        database.openHelper.writableDatabase
    } finally {
        database.close()
    }
}

internal fun RestoreTransaction.copySafOnce(
    context: Context,
    uri: String,
    destination: File,
    faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    sourceOpener: RestoreSourceOpener = RestoreSourceOpener.CONTENT_RESOLVER,
    expectedSizeBytes: Long? = null,
    // A plain (non-suspend) poll rather than making this function itself suspend —
    // it is called from several non-coroutine test call sites (see
    // PhaseBRestoreAdmissionInstrumentedTest) that would otherwise need reworking. Callers
    // in a coroutine pass `{ job?.isActive == false }` for real, cooperative cancellation.
    isCancelled: () -> Boolean = { false },
): ByteDigest {
    faults.hit(RestoreFaultPoint.SOURCE_STREAM_READ)
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    sourceOpener.open(context, uri)?.use { input ->
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                if (isCancelled()) throw CancellationException("Backup copy cancelled")
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (expectedSizeBytes != null && read.toLong() > expectedSizeBytes - count) {
                    error("Selected backup exceeds its declared size")
                }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                count += read
            }
            output.fd.sync()
        }
    } ?: error("Could not read the selected backup")
    return ByteDigest(
        count,
        digest.digest().joinToString("") { byte -> "%02x".format(byte) },
    )
}

internal fun RestoreTransaction.ensureSpace(
    dir: File,
    liveSize: Long,
    candidateSize: Long,
    faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
    availableBytes: Long = StatFs(dir.path).availableBytes,
) {
    faults.hit(RestoreFaultPoint.FREE_SPACE_PREFLIGHT)
    check(availableBytes >= RestoreStorage.requiredBytes(liveSize, candidateSize)) {
        "Not enough internal storage to restore safely"
    }
}
