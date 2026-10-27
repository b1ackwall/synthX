package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup behaviour around the cleanup that follows a COMPLETE restore journal: which failures
 * are tolerated, which stay an emergency, and when the art-recovery sweep flag is set.
 */
@RunWith(AndroidJUnit4::class)
class RestoreCompletedCleanupInstrumentedTest : RestoreScenarioTestBase() {
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

    @Test fun completeJournalDirectorySyncFailureStaysEmergencyAndSkipsCleanup() {
        val scenario = installedScenario()

        assertEquals(RestoreRecovery.EMERGENCY, recover(failOnceAt(RestoreFaultPoint.STATE_JOURNAL_DIRECTORY_SYNC)))

        assertTrue("cleanup must not run before the journal write returns", oldFile(scenario).isFile)
        assertTrue(safetyFile(scenario).isFile)
        assertFalse(artSweepPending())
        assertEquals("candidate", readMarker(liveFile()))

        assertNextStartIsNormal()
        assertFalse(oldFile(scenario).exists())
        assertTrue(artSweepPending())
    }

    @Test fun invalidInstalledLiveWithNoFallbackStaysEmergencyWithoutWritingComplete() {
        val scenario = scenario(
            RestoreTransaction.Phase.POST_VALIDATED,
            installCandidate = true,
        )
        safetyFile(scenario).delete()
        liveFile().writeText("not a database")

        assertEquals(RestoreRecovery.EMERGENCY, recover())

        assertEquals(RestoreTransaction.Phase.POST_VALIDATED, readState().phase)
        assertFalse(artSweepPending())
    }

    @Test fun rolledBackCleanupSyncFailureStaysEmergency() {
        rolledBackScenario()

        assertEquals(
            RestoreRecovery.EMERGENCY,
            recover(failOnceAt(RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC)),
        )

        assertFalse(artSweepPending())
        assertNextStartIsNormal()
        assertEquals("rolled-back-live", readMarker(liveFile()))
        assertFalse(artSweepPending())
    }

    @Test fun rolledBackCompletionDoesNotSetTheArtSweepFlag() {
        rolledBackScenario()

        assertEquals(RestoreRecovery.NORMAL, recover())

        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
        assertFalse(artSweepPending())
    }

    @Test fun completeHistoryWithLeftoverArtifactsIsCleanedAndSetsTheArtSweepFlag() {
        val scenario = scenario(
            RestoreTransaction.Phase.COMPLETE,
            live = "live",
            old = "original",
        )
        assertTrue(candidateFile(scenario).isFile)

        assertEquals(RestoreRecovery.NORMAL, recover())

        assertFalse(candidateFile(scenario).exists())
        assertFalse(oldFile(scenario).exists())
        assertFalse(safetyFile(scenario).exists())
        assertEquals("live", readMarker(liveFile()))
        assertTrue(artSweepPending())
    }

    @Test fun completeHistoryCleanupCancellationLeavesTheArtSweepFlagAlreadySet() {
        val scenario = scenario(
            RestoreTransaction.Phase.COMPLETE,
            live = "live",
            old = "original",
        )
        assertTrue(candidateFile(scenario).isFile)
        assertFalse("the flag must start cleared", artSweepPending())

        val faults =
            throwingAt(RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC, CancellationException("Injected"))

        assertEquals(RestoreRecovery.EMERGENCY, recover(faults))
        assertTrue("the flag must be set before the cleanup that removes the artifacts", artSweepPending())
    }

    @Test fun rolledBackCompleteHistoryWithLeftoverArtifactsIsCleanedWithoutTheArtSweepFlag() {
        val scenario = scenario(
            RestoreTransaction.Phase.COMPLETE,
            live = "live",
            old = "original",
            outcome = RestoreTransaction.Outcome.ROLLED_BACK,
        )

        assertEquals(RestoreRecovery.NORMAL, recover())

        assertFalse(oldFile(scenario).exists())
        assertFalse(safetyFile(scenario).exists())
        assertFalse(artSweepPending())
    }

    @Test fun cleanedCompleteHistoryDoesNotSetTheArtSweepFlag() {
        val scenario = scenario(RestoreTransaction.Phase.COMPLETE, live = "live")
        candidateFile(scenario).delete()
        safetyFile(scenario).delete()

        assertEquals(RestoreRecovery.NORMAL, recover())

        assertFalse(artSweepPending())
    }

    @Test fun cleanupFailureKeepsTheIncidentUntilCleanupSucceeds() {
        val incident = File(databaseDir, "synthx.restore-incident.id")
        incident.writeText(newOp())
        val scenario = installedScenario()

        recover(failOnceAt(RestoreFaultPoint.SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC))

        assertTrue("a failed cleanup must not clear the incident", incident.isFile)
        assertTrue(oldFile(scenario).isFile)

        assertNextStartIsNormal()
        assertFalse("a finished cleanup clears the incident", incident.exists())
    }

    @Test fun artSweepFlagIsSetWhenSafetyPromotionCleanupSyncFails() {
        installedScenario()

        recover(failOnceAt(RestoreFaultPoint.SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC))

        assertTrue(artSweepPending())
    }

    @Test fun cancellationDuringCleanupIsNotSwallowed() {
        installedScenario()

        val faults =
            throwingAt(RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC, CancellationException("Injected"))

        assertEquals(RestoreRecovery.EMERGENCY, recover(faults))
    }

    @Test fun errorDuringCleanupStaysEmergencyAfterTheArtSweepFlagIsSet() {
        installedScenario()

        val faults = throwingAt(RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC, AssertionError("Injected"))

        assertEquals(RestoreRecovery.EMERGENCY, recover(faults))
        assertTrue(artSweepPending())
    }

    @Test fun unreachableBackupFolderDuringPromotionStartsNormalAndKeepsTheSafetyCopy() {
        PreferencesManager(context).backupFolderUri = UNREACHABLE_FOLDER
        val scenario = installedScenario()

        assertEquals(RestoreRecovery.NORMAL, recover())

        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals("candidate", readMarker(liveFile()))
        assertTrue("the staged safety copy waits for a retry", stagedFile(scenario).isFile)
        assertTrue(oldFile(scenario).isFile)
        assertTrue(artSweepPending())
    }

    @Test fun repeatedCleanupFailureAtTheNextStartStaysNormalUntilCleanupSucceeds() {
        PreferencesManager(context).backupFolderUri = UNREACHABLE_FOLDER
        val scenario = installedScenario()
        assertEquals(RestoreRecovery.NORMAL, recover())

        RestoreCoordinator.resetForTest()
        assertEquals(RestoreRecovery.NORMAL, recover())
        assertTrue(stagedFile(scenario).isFile)
        assertEquals("candidate", readMarker(liveFile()))

        PreferencesManager(context).backupFolderUri = null
        assertNextStartIsNormal()
        assertFalse(stagedFile(scenario).exists())
        assertFalse(oldFile(scenario).exists())
        assertEquals("candidate", readMarker(liveFile()))
    }

    private fun installedScenario() = scenario(
        RestoreTransaction.Phase.POST_VALIDATED,
        old = "original",
        installCandidate = true,
    )

    private fun rolledBackScenario() = scenario(
        RestoreTransaction.Phase.ROLLED_BACK,
        live = "rolled-back-live",
        old = "original",
        outcome = RestoreTransaction.Outcome.ROLLED_BACK,
        rollbackSource = RestoreTransaction.RollbackSource.OLD,
    )

    private fun recover(faults: RestoreFaultInjector = RestoreFaultInjector.NONE): RestoreRecovery =
        RestoreTransaction.recoverIfNeeded(context, databaseDir, faults)

    private fun throwingAt(point: RestoreFaultPoint, failure: Throwable) = RestoreFaultInjector { actual ->
        if (actual == point) throw failure
    }

    private fun assertNextStartIsNormal() {
        RestoreCoordinator.resetForTest()
        assertEquals(RestoreRecovery.NORMAL, recover())
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
    }

    private fun artSweepPending() = PreferencesManager(context).artSweepPending

    private fun candidateFile(scenario: Scenario) = File(databaseDir, scenario.state.candidateName)

    private fun oldFile(scenario: Scenario) = File(databaseDir, scenario.state.oldName)

    private fun safetyFile(scenario: Scenario) = File(databaseDir, scenario.state.safetyName)

    private fun stagedFile(scenario: Scenario) =
        File(databaseDir, RestoreArtifactNames(scenario.state.operationId).safetyPromotion)

    private companion object {
        const val UNREACHABLE_FOLDER = "content://com.v1per.synthx.test.nowhere/export"
    }
}
