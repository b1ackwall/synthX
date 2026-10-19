package com.v1per.synthx.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.v1per.synthx.preferences.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records every durability point a successful restore and the next cold start pass, and checks
 * that each recovery-critical rename is directly followed by its own directory sync. A failure
 * injected after a moved sync looks the same as one before it, so only this order check sees a
 * sync moved ahead of its rename. Only adjacent pairs are checked, so new points do not break it.
 */
@RunWith(AndroidJUnit4::class)
class RestoreFaultPointOrderInstrumentedTest : RestoreScenarioTestBase() {
    private var savedBackupFolder: String? = null

    @Before fun isolatePreferences() {
        val preferences = PreferencesManager(context)
        savedBackupFolder = preferences.backupFolderUri
        preferences.backupFolderUri = null
    }

    @After fun restorePreferences() {
        PreferencesManager(context).backupFolderUri = savedBackupFolder
    }

    @Test fun happyPathSyncsDirectlyAfterEachRenameAndBeforeTheNextStep() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")
        val restore = RecordingFaultInjector()

        val result = execute(source, metadata(source), restore)

        assertTrue("restore failed: ${result.exceptionOrNull()}", result.isSuccess)
        val hits = restore.hits
        assertFollowedBy(hits, RestoreFaultPoint.SOURCE_STREAM_READ, RestoreFaultPoint.SOURCE_SHA_VERIFY)
        assertFollowedBy(hits, RestoreFaultPoint.LIVE_TO_OLD_RENAME, RestoreFaultPoint.LIVE_TO_OLD_DIRECTORY_SYNC)
        assertFollowedBy(
            hits,
            RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME,
            RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC,
        )
        assertFollowedBy(
            hits,
            RestoreFaultPoint.LIVE_TO_OLD_DIRECTORY_SYNC,
            RestoreFaultPoint.CANDIDATE_TO_LIVE_RENAME,
            adjacent = false,
        )
        assertFollowedBy(
            hits,
            RestoreFaultPoint.CANDIDATE_TO_LIVE_DIRECTORY_SYNC,
            RestoreFaultPoint.INSTALLED_VALIDATION,
            adjacent = false,
        )

        RestoreCoordinator.resetForTest()
        val startup = RecordingFaultInjector()
        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir, startup))
        assertFollowedBy(
            startup.hits,
            RestoreFaultPoint.SAFETY_PROMOTION_CLEANUP_DIRECTORY_SYNC,
            RestoreFaultPoint.POST_COMPLETE_CLEANUP_DIRECTORY_SYNC,
        )
        assertEquals("restored", readMarker(liveFile()))
    }

    @Test fun startupRollbackSyncsDirectlyAfterPromotingOld() {
        scenario(RestoreTransaction.Phase.LIVE_MOVED, old = "original")
        val startup = RecordingFaultInjector()

        assertEquals(RestoreRecovery.NORMAL, RestoreTransaction.recoverIfNeeded(context, databaseDir, startup))

        assertFollowedBy(
            startup.hits,
            RestoreFaultPoint.OLD_ROLLBACK_RENAME,
            RestoreFaultPoint.STARTUP_ROLLBACK_DIRECTORY_SYNC,
        )
        assertEquals("original", readMarker(liveFile()))
    }

    @Test fun currentProcessRollbackSyncsDirectlyAfterPromotingOld() = runBlocking {
        createLive("original")
        val source = newTrustedSource("restored")
        val recorder = RecordingFaultInjector()
        val trigger = failOnceAt(RestoreFaultPoint.INSTALLED_VALIDATION)

        val result = execute(source, metadata(source)) { point ->
            recorder.hit(point)
            trigger.hit(point)
        }

        assertTrue(result.exceptionOrNull() is RestoreTransaction.PostCloseFailure)
        assertFollowedBy(
            recorder.hits,
            RestoreFaultPoint.OLD_ROLLBACK_RENAME,
            RestoreFaultPoint.CURRENT_ROLLBACK_DIRECTORY_SYNC,
        )
        assertEquals("original", readMarker(liveFile()))
    }
}
