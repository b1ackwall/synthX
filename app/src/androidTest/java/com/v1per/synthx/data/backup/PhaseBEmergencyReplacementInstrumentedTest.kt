package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.data.db.SynthXDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RestoreTransaction.executeEmergencyReplacement and BackupManager.emergencyRestore: the guards
 * that run before the journal exists, the replay no-op, and the failure path before and after the
 * live database is parked. These pin current behaviour, including the lack of an in-process
 * rollback once the live file has been parked.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBEmergencyReplacementInstrumentedTest : PhaseBRestoreTestBase() {
    private val operationId = UUID.randomUUID().toString().replace("-", "")
    private val names = RestoreArtifactNames(operationId)

    private suspend fun emergency(
        source: File,
        metadata: BackupMetadata = metadata(source),
        faults: RestoreFaultInjector = RestoreFaultInjector.NONE,
        requestId: String = operationId,
    ): Result<Unit> = RestoreTransaction.executeEmergencyReplacement(
        context,
        backupInfo(source),
        metadata,
        requestId,
        faults,
    )

    private fun writeUnusableLive(): ByteArray {
        liveFile().writeText(UNUSABLE_LIVE)
        return liveFile().readBytes()
    }

    private fun restoreArtifacts(): List<String> = databaseDir.listFiles().orEmpty()
        .map(File::getName)
        .filter { RestoreArtifactNames.parseOperationId(it) != null }
        .sorted()

    private fun incidentIdFile() = File(databaseDir, "synthx.restore-incident.id")

    private fun incidentReports(): List<File> =
        File(context.filesDir, "restore_incidents").listFiles().orEmpty().filter { it.name.endsWith(".json") }

    private fun assertNoIncident() {
        assertFalse("incident id published", incidentIdFile().exists())
        assertEquals(emptyList<File>(), incidentReports())
    }

    private fun assertEmergencyFailureIncident(failure: Throwable) {
        val id = incidentIdFile().readText().trim()
        assertEquals(operationId, id)
        val report = RestoreIncidentReports.decode(RestoreIncidentReports.reportFile(context, id))
        assertEquals("EMERGENCY_REPLACEMENT_FAILURE", report.triggerCategory)
        assertTrue(
            "report lacks '${failure.message}': ${report.exceptions}",
            report.exceptions.any { it.message == failure.message },
        )
    }

    private fun assertStillEmergency() {
        assertEquals(RestoreCoordinator.Mode.EMERGENCY, RestoreCoordinator.currentMode())
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
    }

    // ------------------------------------------------------------ guards before the try

    @Test fun outsideEmergencyModeIsRejectedWithoutTouchingAnything() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")

        val failure = emergency(source).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Emergency replacement is available only while database recovery is locked", failure?.message)
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertNoIncident()
    }

    @Test fun restartRequiredModeIsRejectedWithoutTouchingAnything() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markRestartRequired(rolledBack = false)

        val failure = emergency(source).exceptionOrNull()

        assertEquals("Emergency replacement is available only while database recovery is locked", failure?.message)
        assertEquals(RestoreCoordinator.Mode.RESTART_REQUIRED, RestoreCoordinator.currentMode())
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertNoIncident()
    }

    @Test fun newerSchemaBackupIsRejectedWithoutTouchingAnything() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val newer = metadata(source).copy(dbVersion = SynthXDatabase.VERSION + 1)
        val failure = emergency(source, newer).exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Selected backup is not eligible for emergency recovery", failure?.message)
        assertStillEmergency()
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertNoIncident()
    }

    @Test fun metadataNamingAnotherFileIsRejectedWithoutTouchingAnything() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val misnamed = metadata(source).copy(file = validBackupName())
        val failure = emergency(source, misnamed).exceptionOrNull()

        assertEquals("Selected backup is not eligible for emergency recovery", failure?.message)
        assertStillEmergency()
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertNoIncident()
    }

    @Test fun metadataWithoutDigestIsRejectedAsIneligible() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val failure = emergency(source, metadata(source).copy(sha256 = null)).exceptionOrNull()

        assertEquals("Selected backup is not eligible for emergency recovery", failure?.message)
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertNoIncident()
    }

    // ------------------------------------------------------------ replay

    @Test fun replayOfACompletedRequestIdSucceedsWithoutChangingAnything() = runBlocking {
        liveFile().writeText(UNUSABLE_LIVE)
        val first = newTrustedSource("first")
        RestoreCoordinator.markEmergency("original-incident")
        assertTrue(emergency(first).isSuccess)
        // Seed what a later cold start's recovery records once the restore is complete.
        val completed = readState().copy(completedRequestIds = listOf(operationId))
        RestoreJournalTestAccess.write(databaseDir, completed)
        val journalBytes = stateFile().readBytes()
        val liveBytes = liveFile().readBytes()
        val artifacts = restoreArtifacts()
        RestoreCoordinator.resetForTest()
        RestoreCoordinator.markEmergency("second-incident")
        val second = newTrustedSource("second")

        val result = emergency(second)

        assertTrue(result.isSuccess)
        assertStillEmergency()
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals("first", readMarker(liveFile()))
        assertTrue(journalBytes.contentEquals(stateFile().readBytes()))
        assertEquals(artifacts, restoreArtifacts())
    }

    // ------------------------------------------------------------ BackupManager.emergencyRestore

    @Test fun emergencyRestoreWithoutMetadataFailsBeforeAnyRestoreStep() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val result = BackupManager.create(context).emergencyRestore(backupInfo(source), operationId)

        val failure = result.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(context.getString(com.v1per.synthx.R.string.restore_ineligible_missing_metadata), failure?.message)
        assertStillEmergency()
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertNoIncident()
    }

    // ------------------------------------------------------------ failure before the live file is parked

    @Test fun failureBeforeParkingLeavesLiveInPlaceAndDeletesUnownedArtifacts() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val failure = emergency(source, faults = failOnceAt(RestoreFaultPoint.SOURCE_STREAM_READ)).exceptionOrNull()

        requireNotNull(failure)
        assertEquals("Injected SOURCE_STREAM_READ", failure.message)
        assertStillEmergency()
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertEmergencyFailureIncident(failure)
    }

    @Test fun failureBeforeParkingKeepsAnotherOperationsJournalAndItsFiles() = runBlocking {
        liveFile().writeText(UNUSABLE_LIVE)
        val first = newTrustedSource("first")
        RestoreCoordinator.markEmergency("original-incident")
        val otherOperation = UUID.randomUUID().toString().replace("-", "")
        assertTrue(emergency(first, requestId = otherOperation).isSuccess)
        val otherJournal = stateFile().readBytes()
        val otherLive = liveFile().readBytes()
        val otherArtifacts = restoreArtifacts()
        RestoreCoordinator.resetForTest()
        RestoreCoordinator.markEmergency("second-incident")
        val second = newTrustedSource("second")

        val failure = emergency(second, faults = failOnceAt(RestoreFaultPoint.SOURCE_STREAM_READ)).exceptionOrNull()

        assertEquals("Injected SOURCE_STREAM_READ", failure?.message)
        assertTrue(otherJournal.contentEquals(stateFile().readBytes()))
        assertTrue(otherLive.contentEquals(liveFile().readBytes()))
        assertEquals(otherArtifacts, restoreArtifacts())
        assertStillEmergency()
    }

    @Test fun backupBytesThatDifferFromTheMetadataDigestFailBeforeParking() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")
        val wrongDigest = metadata(source).copy(sha256 = "0".repeat(64))

        val failure = emergency(source, wrongDigest).exceptionOrNull()

        requireNotNull(failure)
        assertEquals("Backup bytes do not match verified metadata", failure.message)
        assertTrue(liveBytes.contentEquals(liveFile().readBytes()))
        assertEquals(emptyList<String>(), restoreArtifacts())
        assertFalse(stateFile().exists())
        assertEmergencyFailureIncident(failure)
        assertStillEmergency()
    }

    // ------------------------------------------------------------ failure after the live file is parked

    @Test fun failureOnInstallRenameLeavesNoLiveFileButKeepsParkedLiveCandidateAndSafety() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val failure = emergency(
            source,
            faults = failOnceAt(RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME),
        ).exceptionOrNull()

        requireNotNull(failure)
        assertEquals("Injected CANDIDATE_TO_LIVE_RENAME", failure.message)
        assertStillEmergency()
        assertFalse(liveFile().exists())
        assertEquals(listOf(names.candidate, names.failed, names.safety), restoreArtifacts())
        assertTrue(liveBytes.contentEquals(ownedFile(names.failed).readBytes()))
        assertEquals("replacement", readMarker(ownedFile(names.candidate)))
        assertEquals("replacement", readMarker(ownedFile(names.safety)))
        assertEquals(operationId, readState().operationId)
        assertEquals(RestoreTransaction.Phase.INSTALL_INTENT, readState().phase)
        assertEmergencyFailureIncident(failure)
    }

    @Test fun failureOnInstallDirectorySyncLeavesTheCandidateLiveWithoutRollback() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")

        val failure = emergency(
            source,
            faults = failOnceAt(RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC),
        ).exceptionOrNull()

        requireNotNull(failure)
        assertEquals("Injected CANDIDATE_TO_LIVE_DIRECTORY_SYNC", failure.message)
        assertStillEmergency()
        assertEquals("replacement", readMarker(liveFile()))
        assertEquals(listOf(names.failed, names.safety), restoreArtifacts())
        assertTrue(liveBytes.contentEquals(ownedFile(names.failed).readBytes()))
        assertEquals(RestoreTransaction.Phase.INSTALL_INTENT, readState().phase)
        assertEmergencyFailureIncident(failure)
    }

    @Test fun retryWithAFreshIdAfterInstallRenameFailureSucceedsAndLeavesTheFirstAttemptsFiles() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")
        assertTrue(
            emergency(source, faults = failOnceAt(RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME)).isFailure,
        )
        val retryId = UUID.randomUUID().toString().replace("-", "")

        val retry = emergency(source, requestId = retryId)

        assertTrue(retry.isSuccess)
        assertEquals(RestoreCoordinator.Terminal.RESTART_REQUIRED, RestoreCoordinator.current())
        assertEquals("replacement", readMarker(liveFile()))
        assertEquals(retryId, readState().operationId)
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, readState().phase)
        // The first attempt's artifacts are no longer referenced by the journal and stay on disk.
        val retryNames = RestoreArtifactNames(retryId)
        assertEquals(
            listOf(names.candidate, names.failed, names.safety, retryNames.safety).sorted(),
            restoreArtifacts(),
        )
        assertTrue(liveBytes.contentEquals(ownedFile(names.failed).readBytes()))
    }

    @Test fun startupRecoveryAfterInstallRenameFailurePromotesTheEmergencyFallbackCopy() = runBlocking {
        val liveBytes = writeUnusableLive()
        val source = newTrustedSource("replacement")
        RestoreCoordinator.markEmergency("original-incident")
        assertTrue(
            emergency(source, faults = failOnceAt(RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME)).isFailure,
        )

        val recovery = RestoreTransaction.recoverIfNeeded(context, databaseDir)

        assertEquals(RestoreRecovery.NORMAL, recovery)
        assertEquals("replacement", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
        assertEquals(listOf(names.failed), restoreArtifacts())
        assertTrue(liveBytes.contentEquals(ownedFile(names.failed).readBytes()))
    }

    private companion object {
        const val UNUSABLE_LIVE = "unusable live database"
    }
}
