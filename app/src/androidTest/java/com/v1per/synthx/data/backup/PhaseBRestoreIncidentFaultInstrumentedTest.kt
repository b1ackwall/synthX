package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B restore: every fault point in the incident id and incident report persistence is reached,
 * still ends in EMERGENCY with a recovery id, and leaves the files described per point.
 * The id is persisted twice (stateless recovery, then incident publication). The second pass finds the
 * promoted id file, so its rename fails and leaves the id temp behind; that leftover is pinned here.
 */
@RunWith(AndroidJUnit4::class)
class PhaseBRestoreIncidentFaultInstrumentedTest : RestoreScenarioTestBase() {

    private val idFile get() = File(databaseDir, "synthx.restore-incident.id")
    private val idTemp get() = File(databaseDir, "synthx.restore-incident.id.tmp")
    private val idArchive get() = File(databaseDir, "synthx.restore-incident.id.invalid")
    private val reportDirectory get() = File(context.filesDir, "restore_incidents")

    private data class Outcome(val id: String, val faults: RecordingFaults)

    private fun reportTemp(id: String) =
        File(reportDirectory, ".${RestoreIncidentReports.reportFile(context, id).name}.tmp")

    /** Malformed journal, no artifacts: recovery picks a recovery id and publishes the incident. */
    private fun recoverWithMalformedState(failAt: RestoreFaultPoint?, seedInvalidIdFile: Boolean = false): Outcome {
        cleanOwnedFiles()
        reportDirectory.listFiles().orEmpty().forEach(File::delete)
        stateFile().writeText("bad state")
        if (seedInvalidIdFile) idFile.writeText("invalid")
        val faults = RecordingFaults(failAt)
        assertEquals(
            "fault $failAt escaped the emergency boundary",
            RestoreRecovery.EMERGENCY,
            RestoreTransaction.recoverIfNeeded(context, databaseDir, faults),
        )
        val id = RestoreTransaction.diagnosticOperationId().orEmpty()
        assertTrue("diagnostic id was $id", id.startsWith("recovery-"))
        if (failAt != null) assertTrue("$failAt was never hit; hits=${faults.hits}", failAt in faults.hits)
        return Outcome(id, faults)
    }

    private fun assertIdFile(expected: String?) = assertEquals(expected, idFile.takeIf(File::isFile)?.readText())

    private fun assertIdTemp(expected: String?) = assertEquals(expected, idTemp.takeIf(File::exists)?.readText())

    private fun assertReportPublished(id: String) {
        val report = RestoreIncidentReports.decode(RestoreIncidentReports.reportFile(context, id))
        assertEquals(id, report.incidentId)
        assertFalse(reportTemp(id).exists())
    }

    private fun assertNoReport(id: String) = assertFalse(RestoreIncidentReports.reportFile(context, id).exists())

    @Test fun incidentPersistenceWithoutFaultsHitsEveryPointAndLeavesCompleteFiles() {
        val (id, faults) = recoverWithMalformedState(failAt = null)
        val incidentPoints = RestoreFaultPoint.entries.filter { it.name.startsWith("INCIDENT_") }
        val expectedHit = incidentPoints - RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME
        assertEquals(expectedHit.toSet(), faults.hits.filter { it in incidentPoints }.toSet())
        assertIdFile(id)
        assertIdTemp(id)
        assertReportPublished(id)
    }

    @Test fun incidentIdOpenFailureLeavesNoIdFileAndStillPublishesReport() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_ID_OPEN)
        assertIdFile(null)
        assertIdTemp(null)
        assertReportPublished(id)
    }

    @Test fun incidentIdWriteFailureLeavesAnEmptyTempAndNoIdFile() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_ID_WRITE)
        assertIdFile(null)
        assertIdTemp("")
        assertReportPublished(id)
    }

    @Test fun incidentIdFileSyncFailureLeavesTheWrittenTempAndNoIdFile() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_ID_FILE_SYNC)
        assertIdFile(null)
        assertIdTemp(id)
        assertReportPublished(id)
    }

    @Test fun incidentIdArchiveRenameFailureKeepsTheInvalidFileAndTheTemp() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME, seedInvalidIdFile = true)
        assertIdFile("invalid")
        assertIdTemp(id)
        assertFalse(idArchive.exists())
        assertReportPublished(id)
    }

    @Test fun incidentIdArchiveRenameSuccessArchivesTheInvalidFileAndPromotesTheId() {
        val (id, faults) = recoverWithMalformedState(failAt = null, seedInvalidIdFile = true)
        assertTrue(RestoreFaultPoint.INCIDENT_ID_ARCHIVE_RENAME in faults.hits)
        assertEquals("invalid", idArchive.readText())
        assertIdFile(id)
        assertIdTemp(id)
    }

    @Test fun incidentIdPromoteRenameFailureLeavesTheTempAndNoIdFile() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_ID_PROMOTE_RENAME)
        assertIdFile(null)
        assertIdTemp(id)
        assertReportPublished(id)
    }

    @Test fun incidentDirectorySyncFailureKeepsThePromotedIdFile() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_DIRECTORY_SYNC)
        assertIdFile(id)
        assertIdTemp(id)
        assertReportPublished(id)
    }

    @Test fun incidentReportOpenFailureLeavesNoReportAndNoTemp() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_REPORT_OPEN)
        assertIdFile(id)
        assertIdTemp(id)
        assertNoReport(id)
        assertFalse(reportTemp(id).exists())
    }

    @Test fun incidentReportWriteFailureLeavesAnEmptyTempAndNoReport() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_REPORT_WRITE)
        assertIdFile(id)
        assertIdTemp(id)
        assertNoReport(id)
        assertEquals(0L, reportTemp(id).length())
        assertTrue(reportTemp(id).exists())
    }

    @Test fun incidentReportFileSyncFailureLeavesTheWrittenTempAndNoReport() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_REPORT_FILE_SYNC)
        assertIdFile(id)
        assertIdTemp(id)
        assertNoReport(id)
        assertTrue(reportTemp(id).length() > 0L)
    }

    @Test fun incidentReportPromoteRenameFailureLeavesTheWrittenTempAndNoReport() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_REPORT_PROMOTE_RENAME)
        assertIdFile(id)
        assertIdTemp(id)
        assertNoReport(id)
        assertTrue(reportTemp(id).length() > 0L)
    }

    @Test fun incidentReportDirectorySyncFailureKeepsThePromotedReport() {
        val (id, _) = recoverWithMalformedState(RestoreFaultPoint.INCIDENT_REPORT_DIRECTORY_SYNC)
        assertIdFile(id)
        assertIdTemp(id)
        assertReportPublished(id)
    }
}
