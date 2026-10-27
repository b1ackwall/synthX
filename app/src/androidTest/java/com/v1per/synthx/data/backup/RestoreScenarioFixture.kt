package com.v1per.synthx.data.backup

import android.database.sqlite.SQLiteDatabase
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Shared restore fixtures for the device suites: a hand-made journal plus real database files
 * ([scenario]), fault injectors, and journal access.
 *
 * Abstract and without `@RunWith`, like [PhaseBRestoreTestBase], so the runner does not collect it.
 */
abstract class RestoreScenarioTestBase : PhaseBRestoreTestBase() {

    internal data class Scenario(val state: RestoreTransaction.State)

    /**
     * Writes a journal at [phase] with a real candidate and safety file, plus optional live and
     * `old` files holding the given markers. [installCandidate] renames the candidate onto live.
     */
    internal fun scenario(
        phase: RestoreTransaction.Phase,
        live: String? = null,
        old: String? = null,
        installCandidate: Boolean = false,
        outcome: RestoreTransaction.Outcome = RestoreTransaction.Outcome.INSTALLED,
        rollbackSource: RestoreTransaction.RollbackSource? = null,
        requestId: String? = null,
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
            rollbackSource = rollbackSource,
            requestId = requestId,
        )
        RestoreJournalTestAccess.write(databaseDir, state)
        if (installCandidate) Os.rename(candidate.path, liveFile().path)
        return Scenario(state)
    }

    protected fun newOp(): String = UUID.randomUUID().toString().replace("-", "")

    /** Every file in the database folder whose name ends with [suffix]. */
    protected fun filesEndingWith(suffix: String): List<File> =
        databaseDir.listFiles().orEmpty().filter { it.name.endsWith(suffix) }

    internal fun updateMarker(file: File, value: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL(
                "UPDATE playlist_groups SET name=? WHERE id='marker'",
                arrayOf(value),
            )
        }
        assertEquals(value, readMarker(file))
        BackupSnapshotValidator.validate(file, false)
    }
}

/** Fails the first time [point] is hit with "Injected <point>", then lets every later hit pass. */
internal fun failOnceAt(point: RestoreFaultPoint): RestoreFaultInjector {
    var fired = false
    return RestoreFaultInjector { actual ->
        if (!fired && actual == point) {
            fired = true
            error("Injected $point")
        }
    }
}

/**
 * Records every point hit, in order, and throws "Injected <point>" on each hit of [failAt] (never when null),
 * so a test can prove the fault point was reached as well as what the failure left behind.
 */
internal class RecordingFaults(private val failAt: RestoreFaultPoint? = null) : RestoreFaultInjector {
    val hits = mutableListOf<RestoreFaultPoint>()

    override fun hit(point: RestoreFaultPoint) {
        hits += point
        if (point == failAt) error("Injected $point")
    }
}

/** Fails once at [trigger] (to reach a rollback path), then once at [point]. */
internal fun failAfterTrigger(trigger: RestoreFaultPoint, point: RestoreFaultPoint): RestoreFaultInjector {
    val first = failOnceAt(trigger)
    val second = failOnceAt(point)
    return RestoreFaultInjector { actual ->
        first.hit(actual)
        second.hit(actual)
    }
}

/** Records every fault point hit, in order, without failing. */
internal class RecordingFaultInjector : RestoreFaultInjector {
    val hits = mutableListOf<RestoreFaultPoint>()
    override fun hit(point: RestoreFaultPoint) {
        hits += point
    }
}

/**
 * Checks that [then] follows the first hit of [first]: directly after it when [adjacent], or
 * anywhere later otherwise.
 */
internal fun assertFollowedBy(
    hits: List<RestoreFaultPoint>,
    first: RestoreFaultPoint,
    then: RestoreFaultPoint,
    adjacent: Boolean = true,
) {
    val index = hits.indexOf(first)
    assertTrue("$first never hit: $hits", index >= 0)
    if (adjacent) {
        assertEquals("point directly after $first in $hits", then, hits.getOrNull(index + 1))
    } else {
        assertTrue("$then not after $first in $hits", then in hits.subList(index + 1, hits.size))
    }
}

/**
 * Shortcuts to the journal's test seams in RestoreTransactionJournal.kt. Some device tests also call
 * RestoreTransaction.decodeStateOrNullForTest directly.
 */
internal object RestoreJournalTestAccess {
    fun write(dir: File, state: RestoreTransaction.State) = RestoreTransaction.writeStateForTest(dir, state)

    fun decode(text: String): RestoreTransaction.State? = RestoreTransaction.decodeStateOrNullForTest(text)
}

/**
 * Golden journal bytes from `app/src/androidTest/assets/restore-journals/` (a byte copy of the
 * JVM folder; README.md there explains how the active golden follows a database version bump).
 */
internal object RestoreJournalGoldens {
    const val OPERATION_ID = "0123456789abcdef0123456789abcdef"

    fun read(name: String): String = InstrumentationRegistry.getInstrumentation().context.assets
        .open("restore-journals/$name").bufferedReader().use { it.readText() }

    /**
     * [text] with [from] replaced by [to]. [from] must occur exactly once, so a golden that no
     * longer holds the expected text fails here instead of silently changing meaning.
     */
    fun replaceOnce(text: String, from: String, to: String): String {
        val count = text.split(from).size - 1
        check(count == 1) { "Golden text holds '$from' $count times, expected once" }
        return text.replace(from, to)
    }
}
