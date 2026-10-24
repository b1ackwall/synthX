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
 * A failure injected at each startup-recovery durability point that no other test reaches. After
 * each one the journal and files show how far recovery got, and the next start is NORMAL with the
 * right data. The start itself is EMERGENCY, except for failures in the cleanup that follows a
 * COMPLETE journal: those are logged and the start is NORMAL.
 */
@RunWith(AndroidJUnit4::class)
class RestoreRecoverySyncFailureInstrumentedTest : RestoreScenarioTestBase() {
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

    @Test fun startupRollbackSyncFailureIsEmergencyThenNextStartCompletes() {
        scenario(RestoreTransaction.Phase.LIVE_MOVED, old = "original")

        assertEquals(RestoreRecovery.EMERGENCY, recoverWith(RestoreFaultPoint.STARTUP_ROLLBACK_DIRECTORY_SYNC))

        assertEquals(RestoreTransaction.Phase.ROLLBACK_INTENT, readState().phase)
        assertEquals(RestoreTransaction.RollbackSource.OLD, readState().rollbackSource)
        assertEquals("original", readMarker(liveFile()))
        assertNextStartIsNormalWith("original", RestoreTransaction.Outcome.ROLLED_BACK)
    }

    @Test fun preCloseCleanupSyncFailureIsEmergencyThenNextStartIsNormal() {
        val scenario = scenario(RestoreTransaction.Phase.PREPARED, live = "original")

        assertEquals(RestoreRecovery.EMERGENCY, recoverWith(RestoreFaultPoint.PRE_CLOSE_CLEANUP_DIRECTORY_SYNC))

        assertFalse(File(databaseDir, scenario.state.candidateName).exists())
        assertFalse(File(databaseDir, scenario.state.safetyName).exists())
        assertFalse(stateFile().exists())
        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
    }

    @Test fun postCompleteCleanupSyncFailureStartsNormalWithTheRestoredData() {
        val scenario = scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)

        assertEquals(RestoreRecovery.NORMAL, recoverWith(RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC))

        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals("candidate", readMarker(liveFile()))
        assertFalse(File(databaseDir, scenario.state.oldName).exists())
        assertNextStartIsNormalWith("candidate", RestoreTransaction.Outcome.INSTALLED)
    }

    @Test fun artSweepFlagIsSetWhenPostCompleteCleanupSyncFails() {
        scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)

        recoverWith(RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC)

        assertTrue(PreferencesManager(context).artSweepPending)
    }

    /** Control for the pinned bug: the same restore without the failure sets the flag. */
    @Test fun artSweepFlagIsSetWhenCompletionCleanupSucceeds() {
        scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))

        assertTrue(PreferencesManager(context).artSweepPending)
    }

    @Test fun safetyPromotionCleanupSyncFailureStartsNormalAndTheNextStartRemovesLeftovers() {
        val scenario = scenario(RestoreTransaction.Phase.POST_VALIDATED, old = "original", installCandidate = true)

        assertEquals(RestoreRecovery.NORMAL, recoverWith(RestoreFaultPoint.SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC))

        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertFalse(File(databaseDir, scenario.state.safetyName).exists())
        assertTrue("old is removed only after the promoter", File(databaseDir, scenario.state.oldName).isFile)
        assertEquals("candidate", readMarker(liveFile()))
        assertNextStartIsNormalWith("candidate", RestoreTransaction.Outcome.INSTALLED)
        assertFalse(File(databaseDir, scenario.state.oldName).exists())
    }

    @Test fun corruptStateArchiveSyncFailureIsEmergencyThenNextStartIsNormal() {
        createLive("original")
        val candidate = File(databaseDir, RestoreArtifactNames(newOp()).candidate)
        createDatabaseFile(candidate, "debris")
        stateFile().writeText("bad state")

        assertEquals(RestoreRecovery.EMERGENCY, recoverWith(RestoreFaultPoint.CORRUPT_STATE_ARCHIVE_DIRECTORY_SYNC))

        assertFalse(stateFile().exists())
        assertTrue("artifacts are archived only after the journal", candidate.isFile)
        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
        assertTrue(File(candidate.path + ".recovered").isFile)
    }

    @Test fun recoveredArtifactArchiveSyncFailureIsEmergencyThenNextStartIsNormal() {
        createLive("original")
        val candidate = File(databaseDir, RestoreArtifactNames(newOp()).candidate)
        createDatabaseFile(candidate, "debris")

        assertEquals(
            RestoreRecovery.EMERGENCY,
            recoverWith(RestoreFaultPoint.RECOVERED_ARTIFACT_ARCHIVE_DIRECTORY_SYNC),
        )

        assertFalse(candidate.exists())
        assertTrue(File(candidate.path + ".recovered").isFile)
        assertEquals("original", readMarker(liveFile()))
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals("original", readMarker(liveFile()))
    }

    private fun recoverWith(point: RestoreFaultPoint): RestoreRecovery =
        RestoreTransaction.recoverIfNeeded(context, databaseDir, failOnceAt(point))

    private fun assertNextStartIsNormalWith(marker: String, outcome: RestoreTransaction.Outcome) {
        RestoreCoordinator.resetForTest()
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(marker, readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals(outcome, readState().outcome)
    }
}
