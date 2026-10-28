package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup recovery validates the installed database twice for a POST_VALIDATED journal: once for
 * the planner's facts and once at the start of the completion branch. A failure in the second
 * check is not part of the cleanup that follows a COMPLETE journal, so the start is EMERGENCY and
 * the restore stays unfinished.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRecoveryInstalledValidationInstrumentedTest : RestoreScenarioTestBase() {
    private var savedBackupFolder: String? = null

    @Before fun isolatePreferences() {
        val preferences = PreferencesManager(context)
        savedBackupFolder = preferences.backupFolderUri
        preferences.backupFolderUri = null
        preferences.artSweepPending = false
    }

    @After fun restorePreferences() {
        PreferencesManager(context).backupFolderUri = savedBackupFolder
        PreferencesManager(context).artSweepPending = false
    }

    @Test fun aFailureInTheCompletionBranchsOwnValidationIsEmergencyAndLeavesTheRestoreUnfinished() {
        val scenario = scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)
        var calls = 0

        val result = RestoreTransaction.recoverIfNeeded(
            context,
            databaseDir,
            installedValidator = { _, _ -> if (++calls == 2) error("Injected installed validation") },
        )

        assertEquals(RestoreRecovery.EMERGENCY, result)
        assertEquals(2, calls)
        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, readState().phase)
        assertEquals(scenario.state.operationId, RestoreTransaction.diagnosticOperationId())
        val report = RestoreIncidentReports.decode(
            RestoreIncidentReports.reportFile(context, scenario.state.operationId),
        )
        assertEquals(scenario.state.operationId, report.incidentId)
        assertFalse("the art sweep is flagged only after the validation", PreferencesManager(context).artSweepPending)
        assertEquals("candidate", readMarker(liveFile()))
        assertTrue(File(databaseDir, scenario.state.oldName).isFile)
        assertTrue(File(databaseDir, scenario.state.safetyName).isFile)
    }

    @Test fun aValidatorThatPassesBothChecksCompletesTheRestore() {
        val scenario = scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)
        var calls = 0

        val result = RestoreTransaction.recoverIfNeeded(
            context,
            databaseDir,
            installedValidator = { _, _ -> calls++ },
        )

        assertEquals(RestoreRecovery.NORMAL, result)
        assertEquals(2, calls)
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals("candidate", readMarker(liveFile()))
        assertFalse(File(databaseDir, scenario.state.oldName).exists())
    }

    @Test fun aFailureInThePlannersCheckLeavesTheInstalledDatabaseUnrecognised() {
        scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)

        val result = RestoreTransaction.recoverIfNeeded(
            context,
            databaseDir,
            installedValidator = { _, _ -> error("Injected installed validation") },
        )

        assertEquals(RestoreRecovery.NORMAL, result)
        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
    }
}
