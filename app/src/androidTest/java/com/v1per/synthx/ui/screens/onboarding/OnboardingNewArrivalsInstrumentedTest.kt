package com.v1per.synthx.ui.screens.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.v1per.synthx.R
import com.v1per.synthx.data.backup.ScanStub
import com.v1per.synthx.data.backup.ScanStubWorkerFactory
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A New Arrivals folder picked during Fresh Install is only persisted; its first scan is queued once
 * the first library scan is accepted, so Done's REPLACE has nothing of it to cancel.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingNewArrivalsInstrumentedTest : OnboardingRecoveryTestBase() {

    private fun label(id: Int) = context.getString(id)

    private fun scans(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_WORK_NAME).get()

    private fun newArrivalsScans() = scans().filter { NewArrivalsScanWorker::class.java.name in it.tags }

    private fun libraryScans() = scans().filter { LibraryScanWorker::class.java.name in it.tags }

    private fun pickNewArrivalsInTheWizard() {
        composeRule.runOnUiThread { library.saveOnboardingNewArrivalsFolder(arrivals) }
        await(library.newArrivalsFolderUri) { it == arrivals }
    }

    @Test fun theFreshInstallNewArrivalsPickOnlyPersistsTheFolderEvenWhileAScanRuns() {
        startWizard()
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        val blocker = startBlockingScan()
        val hot = keepNoticeHot()
        try {
            pickNewArrivalsInTheWizard()
            composeRule.waitForIdle()

            assertEquals(arrivals.toString(), preferences.newArrivalsFolderUri)
            assertEquals("no New Arrivals scan queued by the pick", listOf(blocker), scans().map { it.id })
            assertNull(library.newArrivalsFolderNotice.value)
            composeRule.onNodeWithText(label(R.string.status_new_arrivals_scan_deferred)).assertDoesNotExist()
        } finally {
            hot.cancel()
        }
    }

    @Test fun theNewArrivalsScanIsQueuedOnceAfterTheFirstScanIsAccepted() {
        val gate = CompletableDeferred<Unit>()
        ScanStubWorkerFactory.behavior = ScanStub.Gated(gate)
        startWizard()
        composeRule.onNodeWithText(label(R.string.onboarding_fresh_install)).performClick()
        saveOnboardingMusic()
        pickNewArrivalsInTheWizard()
        assertTrue(scans().isEmpty())
        composeRule.onNodeWithText(label(R.string.action_done)).assertIsEnabled().performClick()
        // Done persists the request id synchronously; WorkManager's own record follows.
        val firstScanId = UUID.fromString(requireNotNull(preferences.firstScanRequestId))
        awaitState(firstScanId, WorkInfo.State.RUNNING)
        assertTrue("nothing for New Arrivals while the first scan runs", newArrivalsScans().isEmpty())

        gate.complete(Unit)
        awaitText(HOME_TEXT)

        // Queued by the handover; WorkManager records it on its own thread.
        composeRule.waitUntil(10_000) { newArrivalsScans().isNotEmpty() }
        val arrivalsScan = newArrivalsScans().single()
        awaitState(arrivalsScan.id, WorkInfo.State.SUCCEEDED)
        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(firstScanId).get()?.state)
        assertEquals("one New Arrivals scan, no second", 1, newArrivalsScans().size)
        assertEquals("one library scan", 1, libraryScans().size)
    }

    @Test fun noNewArrivalsScanFollowsTheFirstScanWithoutANewArrivalsFolder() {
        startWizardAtDone()
        awaitText(HOME_TEXT)

        assertNull(preferences.newArrivalsFolderUri)
        assertTrue(newArrivalsScans().isEmpty())
        assertEquals(1, libraryScans().size)
    }

    private companion object {
        const val HOME_TEXT = "Home after onboarding"
    }
}
