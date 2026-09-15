package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Schema-bump regression coverage for retained COMPLETE restore history (P6-T3 / R8-A05). */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreJournalUpgradeInstrumentedTest : PhaseBRestoreTestBase() {
    private var originalBackupFolderUri: String? = null

    @Before
    fun isolateSafetyPromotionDestination() {
        val preferences = PreferencesManager(context)
        originalBackupFolderUri = preferences.backupFolderUri
        preferences.backupFolderUri = null
    }

    @After
    fun restoreSafetyPromotionDestination() {
        PreferencesManager(context).backupFolderUri = originalBackupFolderUri
    }

    @Test
    fun retainedCompleteJournalFromPriorVersionAllowsNormalRoomMigration() = runBlocking {
        createDatabaseAtVersion(liveFile(), priorVersion)
        writeHistoricalState(historicalCompleteState(priorVersion))

        assertEquals(RestoreRecovery.NORMAL, RestoreStartup.recoverNowForTest(context))
        assertEquals(RestoreCoordinator.Terminal.NORMAL, RestoreCoordinator.current())
        assertEquals(
            LiveDatabaseVersion.Present(priorVersion),
            LiveDatabaseVersionReader.read(liveFile()),
        )

        val migrations = AtomicInteger()
        val controller = DatabaseStartupController(
            currentVersion = SynthXDatabase.VERSION,
            awaitRestoreNormal = { RestoreStartup.requireNormal() },
            inspectLiveVersion = { LiveDatabaseVersionReader.read(liveFile()) },
            performMigration = { fromVersion ->
                assertEquals(priorVersion, fromVersion)
                migrations.incrementAndGet()
                val database = SynthXDatabase.getInstanceForMigrationGate(context)
                database.openHelper.writableDatabase
                MigrationInvariantCheck.verify(database, SynthXDatabase.VERSION)
            },
        )
        DatabaseStartupGate.installForTest(controller)

        controller.awaitReady()

        assertEquals(1, migrations.get())
        assertEquals(
            LiveDatabaseVersion.Present(SynthXDatabase.VERSION),
            LiveDatabaseVersionReader.read(liveFile()),
        )
        assertTrue(stateFile().isFile)
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals(priorVersion, readState().candidateVersion)
    }

    @Test
    fun cleanedCompleteHistoryBypassesAllRunningVersionAndLiveChecks() {
        val impossibleRunningVersion = SynthXDatabase.VERSION + 100
        writeHistoricalState(
            historicalCompleteState(
                recordedVersion = impossibleRunningVersion,
                oldVersion = 1,
            )
        )
        assertFalse(liveFile().exists())
        assertNotNull(RestoreTransaction.decodeStateOrNull(stateFile().readText()))

        assertEquals(
            RestoreRecovery.NORMAL,
            RestoreTransaction.recoverIfNeeded(context, databaseDir),
        )
        assertTrue(stateFile().isFile)
        assertFalse(liveFile().exists())
    }

    @Test
    fun olderCompleteHistoryResumesNonSafetyCleanupWithoutSchemaChecks() {
        val state = historicalCompleteState(
            recordedVersion = priorVersion,
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
        )
        createDatabaseAtVersion(File(databaseDir, state.candidateName), priorVersion)
        createDatabaseAtVersion(File(databaseDir, state.oldName), priorVersion)
        writeHistoricalState(state)

        assertEquals(
            RestoreRecovery.NORMAL,
            RestoreTransaction.recoverIfNeeded(context, databaseDir),
        )
        assertFalse(File(databaseDir, state.candidateName).exists())
        assertFalse(File(databaseDir, state.oldName).exists())
        assertTrue(stateFile().isFile)
    }

    @Test
    fun olderSafetySnapshotValidatesAndCleansDuringCompleteCleanupResume() {
        val op = newOperationId()
        val names = RestoreArtifactNames(op)
        val safety = File(databaseDir, names.safety)
        createDatabaseAtVersion(safety, HISTORICAL_SAFETY_VERSION)
        val safetyDigest = BackupHashing.hash(safety)
        val state = historicalCompleteState(
            recordedVersion = HISTORICAL_SAFETY_VERSION,
            operationId = op,
            safetyDigest = safetyDigest,
        )
        writeHistoricalState(state)

        assertEquals(
            RestoreRecovery.NORMAL,
            RestoreTransaction.recoverIfNeeded(context, databaseDir),
        )
        assertFalse(safety.exists())
        assertFalse(File(databaseDir, names.safetyPromotion).exists())
        assertTrue(stateFile().isFile)
    }

    @Test
    fun activeJournalFromOlderVersionRemainsStrictlyRejected() {
        val complete = historicalCompleteState(priorVersion)
        val activeJson = RestoreTransaction.encodeState(complete)
            .replace("\"phase\":\"COMPLETE\"", "\"phase\":\"PREPARED\"")
        assertTrue(activeJson.contains("\"phase\":\"PREPARED\""))
        stateFile().writeText(activeJson)

        assertNull(RestoreTransaction.decodeStateOrNull(activeJson))
        assertEquals(
            RestoreRecovery.EMERGENCY,
            RestoreTransaction.recoverIfNeeded(context, databaseDir),
        )
    }

    private fun historicalCompleteState(
        recordedVersion: Int,
        oldVersion: Int? = recordedVersion,
        outcome: RestoreTransaction.Outcome = RestoreTransaction.Outcome.INSTALLED,
        operationId: String = newOperationId(),
        safetyDigest: ByteDigest = ByteDigest(1, "0".repeat(64)),
    ): RestoreTransaction.State {
        val names = RestoreArtifactNames(operationId)
        return RestoreTransaction.State(
            operationId = operationId,
            sourceName = "historical-restore",
            sourceUri = "content://historical/restore",
            candidateName = names.candidate,
            oldName = names.old,
            safetyName = names.safety,
            failedName = names.failed,
            candidateSha256 = "1".repeat(64),
            candidateSize = 1,
            candidateVersion = recordedVersion,
            safetySha256 = safetyDigest.sha256,
            safetySize = safetyDigest.sizeBytes,
            phase = RestoreTransaction.Phase.COMPLETE,
            createdAt = 1,
            oldVersion = oldVersion,
            outcome = outcome,
        )
    }

    private fun writeHistoricalState(state: RestoreTransaction.State) {
        // This is deliberately serialized independently of the running app's journal writer:
        // on a real upgrade these bytes were written by the prior app version.
        stateFile().writeText(Json.encodeToString(RestoreTransaction.State.serializer(), state))
    }

    private fun createDatabaseAtVersion(destination: File, version: Int) {
        val builderName = "journal-upgrade-${UUID.randomUUID()}.db"
        registeredDatabaseNames += builderName
        migrationHelper.createDatabase(builderName, version).close()
        val created = context.getDatabasePath(builderName)
        deleteFileSet(destination)
        created.copyTo(destination, overwrite = true)
        context.deleteDatabase(builderName)
    }

    private fun newOperationId(): String = UUID.randomUUID().toString().replace("-", "")

    private val priorVersion: Int get() = SynthXDatabase.VERSION - 1

    private companion object {
        const val HISTORICAL_SAFETY_VERSION = 8
    }
}
