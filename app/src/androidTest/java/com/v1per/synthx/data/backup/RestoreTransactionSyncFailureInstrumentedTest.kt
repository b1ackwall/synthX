package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A failure injected at each restore-time durability point that no other test reaches. After each
 * one: the live database still holds the original data, the journal records how far the restore
 * got, and the next cold start (or the rollback already done) ends NORMAL with the original data.
 */
@RunWith(AndroidJUnit4::class)
class RestoreTransactionSyncFailureInstrumentedTest : RestoreScenarioTestBase() {
    private var savedBackupFolder: String? = null

    @Before fun isolateBackupFolder() {
        val preferences = PreferencesManager(context)
        savedBackupFolder = preferences.backupFolderUri
        preferences.backupFolderUri = null
    }

    @After fun restoreBackupFolder() {
        PreferencesManager(context).backupFolderUri = savedBackupFolder
    }

    @Test fun sourceShaVerifyFailureLeavesLiveNormalAndUntouched() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")

        val result = execute(source, metadata(source), failOnceAt(RestoreFaultPoint.SOURCE_SHA_VERIFY))

        assertEquals("Injected SOURCE_SHA_VERIFY", result.exceptionOrNull()?.message)
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
        assertEquals("original", readMarker(liveFile()))
        assertFalse("no journal may exist before admission closes", stateFile().exists())
        assertEquals(emptyList<String>(), filesEndingWith(".candidate.db").map { it.name })
        assertEquals(emptyList<String>(), filesEndingWith(".safety.db").map { it.name })
    }

    @Test fun liveToOldSyncFailureRollsBackToOriginal() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")

        val result = execute(source, metadata(source), failOnceAt(RestoreFaultPoint.LIVE_TO_OLD_DIRECTORY_SYNC))

        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is RestoreTransaction.PostCloseFailure)
        assertEquals("Injected LIVE_TO_OLD_DIRECTORY_SYNC", result.exceptionOrNull()?.cause?.message)
        assertEquals(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED, RestoreCoordinator.current())
        assertEquals("original", readMarker(liveFile()))
        val journal = readState()
        assertEquals(RestoreTransaction.Phase.ROLLED_BACK, journal.phase)
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, journal.outcome)
        assertEquals(RestoreTransaction.RollbackSource.OLD, journal.rollbackSource)
        assertNextStartIsNormalWith("original")
    }

    @Test fun candidateToLiveSyncFailureParksCandidateAndRollsBack() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")

        val result = execute(source, metadata(source), failOnceAt(RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC))

        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is RestoreTransaction.PostCloseFailure)
        assertEquals("Injected CANDIDATE_TO_LIVE_DIRECTORY_SYNC", result.exceptionOrNull()?.cause?.message)
        assertEquals(RestoreCoordinator.Terminal.ROLLED_BACK_RESTART_REQUIRED, RestoreCoordinator.current())
        assertEquals("original", readMarker(liveFile()))
        assertEquals("restored", readMarker(filesEndingWith(".failed.db").single()))
        assertEquals(RestoreTransaction.Phase.ROLLED_BACK, readState().phase)
        assertEquals(RestoreTransaction.RollbackSource.OLD, readState().rollbackSource)
        assertNextStartIsNormalWith("original")
    }

    @Test fun currentRollbackSyncFailureIsEmergencyThenNextStartFinishesRollback() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")
        val faults = failAfterTrigger(
            RestoreFaultPoint.INSTALLED_VALIDATION,
            RestoreFaultPoint.CURRENT_ROLLBACK_DIRECTORY_SYNC,
        )

        val result = execute(source, metadata(source), faults)

        assertEquals("Injected CURRENT_ROLLBACK_DIRECTORY_SYNC", result.exceptionOrNull()?.cause?.message)
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
        assertEquals(RestoreTransaction.Phase.ROLLBACK_INTENT, readState().phase)
        assertEquals(RestoreTransaction.RollbackSource.OLD, readState().rollbackSource)
        assertEquals("the rename before the failed sync already ran", "original", readMarker(liveFile()))
        assertNextStartIsNormalWith("original")
    }

    @Test fun parkLiveSyncFailureIsEmergencyWithOldPreservedThenNextStartPromotesOld() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")
        val faults = failAfterTrigger(
            RestoreFaultPoint.INSTALLED_VALIDATION,
            RestoreFaultPoint.PARK_LIVE_DIRECTORY_SYNC,
        )

        val result = execute(source, metadata(source), faults)

        assertEquals("Injected PARK_LIVE_DIRECTORY_SYNC", result.exceptionOrNull()?.cause?.message)
        assertEquals(RestoreCoordinator.Terminal.EMERGENCY, RestoreCoordinator.current())
        assertEquals(RestoreTransaction.Phase.ROLLBACK_INTENT, readState().phase)
        assertEquals("original", readMarker(filesEndingWith(".old.db").single()))
        assertFalse("the old copy must not be promoted past a failed sync", liveFile().exists())
        assertNextStartIsNormalWith("original")
    }

    private fun assertNextStartIsNormalWith(marker: String) {
        RestoreCoordinator.resetForTest()
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir))
        assertEquals(marker, readMarker(liveFile()))
        assertEquals(RestoreTransaction.Phase.COMPLETE, readState().phase)
        assertEquals(RestoreTransaction.Outcome.ROLLED_BACK, readState().outcome)
    }
}
