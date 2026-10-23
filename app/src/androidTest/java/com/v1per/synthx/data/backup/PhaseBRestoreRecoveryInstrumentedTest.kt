package com.v1per.synthx.data.backup

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B restore: recovery from malformed or missing state, sync failures, incident persistence
 * and request-identity replay through `execute`.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreRecoveryInstrumentedTest : RestoreScenarioTestBase() {

    @Test fun malformedStateWithValidOldRecoversOld() {
        val op = newOp()
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).old), "old")
        stateFile().writeText("{")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("old", readMarker(liveFile()))
    }

    @Test fun malformedStateWithInvalidOldUsesSafety() {
        val op = newOp()
        File(databaseDir, RestoreArtifactNames(op).old).writeText("bad")
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).safety), "safety")
        stateFile().writeText("truncated")
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("safety", readMarker(liveFile()))
    }

    @Test fun missingStateWithValidOldRecoversOld() {
        val op = newOp()
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).old), "old")
        assertFalse(stateFile().exists())
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("old", readMarker(liveFile()))
    }

    @Test fun noValidRecoveryCopyEntersEmergencyWithStableId() {
        val op = newOp()
        File(databaseDir, RestoreArtifactNames(op).old).writeText("bad")
        stateFile().writeText("bad state")
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val first = RestoreTransaction.diagnosticOperationId()
        assertEquals(op, first)
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(first, RestoreTransaction.diagnosticOperationId())
    }

    @Test fun malformedStateWithoutArtifactsGetsStableRecoveryIncidentId() {
        stateFile().writeText("bad state")
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val first = RestoreTransaction.diagnosticOperationId()
        assertTrue(first.orEmpty().startsWith("recovery-"))
        assertEquals(RestoreRecovery.EMERGENCY, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(first, RestoreTransaction.diagnosticOperationId())
    }

    @Test fun candidateOnlyWithoutJournalKeepsValidUntouchedLive() {
        createLive("untouched-live")
        val op = newOp()
        val candidate = File(databaseDir, RestoreArtifactNames(op).candidate)
        createDatabaseFile(candidate, "uncommitted-candidate")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("untouched-live", readMarker(liveFile()))
        assertFalse(candidate.exists())
        assertTrue(File(candidate.path + ".recovered").isFile)
    }

    @Test fun statelessRecoveryIntentRecognizesOldAlreadyInstalledBeforeArchival() {
        val interrupted = scenario(
            RestoreTransaction.Phase.STATELESS_RECOVERY_INTENT,
            old = "recovered-old",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
            rollbackSource = RestoreTransaction.RollbackSource.OLD,
        )
        val old = File(databaseDir, interrupted.state.oldName)
        Os.rename(old.path, liveFile().path)

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("recovered-old", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun statelessRecoveryIntentResumesAfterLiveWasParked() {
        val interrupted = scenario(
            RestoreTransaction.Phase.STATELESS_RECOVERY_INTENT,
            old = "selected-old",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
            rollbackSource = RestoreTransaction.RollbackSource.OLD,
        )
        createDatabaseFile(File(databaseDir, interrupted.state.failedName), "parked-live")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("selected-old", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun rollbackIntentRecognizesConsumedSafetyDespitePresentHashInvalidOld() {
        val interrupted = scenario(
            RestoreTransaction.Phase.ROLLBACK_INTENT,
            old = "recorded-old",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
            rollbackSource = RestoreTransaction.RollbackSource.SAFETY,
        )
        val safety = File(databaseDir, interrupted.state.safetyName)
        Os.rename(safety.path, liveFile().path)
        File(databaseDir, interrupted.state.oldName).writeText("hash-invalid old still present")

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("safety", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun statelessFallbackSyncFailureRecoversOnNextCleanLaunch() {
        val op = newOp()
        createDatabaseFile(File(databaseDir, RestoreArtifactNames(op).old), "old-after-sync-failure")
        val faults = failOnceAt(RestoreFaultPoint.STATELESS_FALLBACK_DIRECTORY_SYNC)

        assertEquals(
            RestoreRecovery.EMERGENCY,
            RestoreTransaction.recoverIfNeeded(context, databaseDir, faults),
        )
        assertEquals("old-after-sync-failure", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.STATELESS_RECOVERY_INTENT, readState().phase)

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("old-after-sync-failure", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    @Test fun safetyPromotionSyncFailurePreservesStagingAndRetriesFromComplete() {
        val pending = scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            old = "pre-restore",
            installCandidate = true,
        )
        val staged = File(
            databaseDir,
            RestoreArtifactNames(pending.state.operationId).safetyPromotion,
        )

        assertEquals(
            RestoreRecovery.EMERGENCY,
            RestoreTransaction.recoverIfNeeded(
                context,
                databaseDir,
                failOnceAt(RestoreFaultPoint.SAFETY_PROMOTION_STAGE_DIRECTORY_SYNC),
            ),
        )
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertTrue(staged.isFile)

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertFalse(staged.exists())
        assertEquals("candidate", readMarker(liveFile()))
    }

    @Test fun incidentDirectorySyncFailureStillReturnsEmergency() {
        stateFile().writeText("bad state")
        assertEquals(
            RestoreRecovery.EMERGENCY,
            RestoreTransaction.recoverIfNeeded(
                context,
                databaseDir,
                failOnceAt(RestoreFaultPoint.INCIDENT_DIRECTORY_SYNC),
            ),
        )
        assertTrue(RestoreTransaction.diagnosticOperationId().orEmpty().startsWith("recovery-"))
    }

    @Test fun recoveryExceptionIsCapturedInIncidentReport() {
        scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)
        assertEquals(
            RestoreRecovery.EMERGENCY,
            RestoreTransaction.recoverIfNeeded(
                context,
                databaseDir,
                failOnceAt(RestoreFaultPoint.STATE_PERSISTENCE),
            ),
        )
        val id = requireNotNull(RestoreTransaction.diagnosticOperationId())
        val report = RestoreIncidentReports.decode(RestoreIncidentReports.reportFile(context, id))
        assertEquals("RECOVERY_EXCEPTION", report.triggerCategory)
        assertTrue(report.exceptions.any { it.message.orEmpty().contains("STATE_PERSISTENCE") })
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED.name, report.journalPhase)
    }

    @Test fun everyIncidentPersistenceFailureStillReturnsEmergency() {
        val points = listOf(
            RestoreFaultPoint.INCIDENT_ID_OPEN,
            RestoreFaultPoint.INCIDENT_ID_WRITE,
            RestoreFaultPoint.INCIDENT_ID_FILE_SYNC,
            RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME,
            RestoreFaultPoint.INCIDENT_ID_PROMOTE_RENAME,
            RestoreFaultPoint.INCIDENT_REPORT_OPEN,
            RestoreFaultPoint.INCIDENT_REPORT_WRITE,
            RestoreFaultPoint.INCIDENT_REPORT_FILE_SYNC,
            RestoreFaultPoint.INCIDENT_REPORT_PROMOTE_RENAME,
            RestoreFaultPoint.INCIDENT_REPORT_DIRECTORY_SYNC,
        )
        points.forEach { point ->
            cleanOwnedFiles()
            File(context.filesDir, "restore_incidents").listFiles().orEmpty().forEach(File::delete)
            stateFile().writeText("bad state")
            if (point == RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME) {
                File(databaseDir, "synthx.restore-incident.id").writeText("invalid")
            }
            assertEquals(
                "fault $point escaped the emergency boundary",
                RestoreRecovery.EMERGENCY,
                RestoreTransaction.recoverIfNeeded(
                    context,
                    databaseDir,
                    RestoreFaultInjector { actual -> if (actual == point) error("injected $point") },
                ),
            )
            assertTrue(RestoreTransaction.diagnosticOperationId().orEmpty().startsWith("recovery-"))
        }
    }

    @Test fun completedInstalledRequestIdentityMakesReplayANoOp() = runBlocking {
        val requestId = newOp()
        scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            old = "original",
            installCandidate = true,
            requestId = requestId,
        )
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        updateMarker(liveFile(), "user-change-after-first-restore")
        val replaySource = newTrustedSource("replay-source")

        assertTrue(
            RestoreTransaction.execute(
                context,
                backupInfo(replaySource),
                metadata(replaySource),
                requestId,
            ).isSuccess,
        )
        assertEquals("user-change-after-first-restore", readMarker(liveFile()))
    }

    @Test fun completedRolledBackRequestIdentityMakesReplayANoOp() = runBlocking {
        val requestId = newOp()
        scenario(
            RestoreTransaction.Phase.ROLLED_BACK,
            live = "rolled-back-live",
            old = "old-copy",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
            requestId = requestId,
        )
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val replaySource = newTrustedSource("must-not-install")

        assertTrue(
            RestoreTransaction.execute(
                context,
                backupInfo(replaySource),
                metadata(replaySource),
                requestId,
            ).isSuccess,
        )
        assertEquals("rolled-back-live", readMarker(liveFile()))
    }

    @Test fun unseenRequestIdentityStillExecutes() = runBlocking {
        val completedRequest = newOp()
        scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            old = "original",
            installCandidate = true,
            requestId = completedRequest,
        )
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        val newSource = newTrustedSource("new-request-installed")

        assertTrue(
            RestoreTransaction.execute(
                context,
                backupInfo(newSource),
                metadata(newSource),
                newOp(),
            ).isSuccess,
        )
        assertEquals("new-request-installed", readMarker(liveFile()))
    }
}
