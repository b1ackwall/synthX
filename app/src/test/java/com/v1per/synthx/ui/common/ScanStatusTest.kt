package com.v1per.synthx.ui.common

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.NewArrivalsScanWorker
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.ui.screens.librarymanagement.FirstScanState
import com.v1per.synthx.ui.screens.librarymanagement.firstScanStateFor
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Covers [scanStatusFor] and the onboarding state mapping built on it. Strings are a
 * fake that renders "<resId>[args]" so assertions pin *which* resource and args were chosen.
 */
class ScanStatusTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val strings = ScanStatusStrings { id, args ->
        if (args.isEmpty()) "$id" else "$id${args.toList()}"
    }

    private fun info(
        state: WorkInfo.State,
        tag: String = LIBRARY_SCAN_TAG,
        output: Data = Data.EMPTY,
        progress: Data = Data.EMPTY,
    ) = WorkInfo(
        id = UUID.randomUUID(),
        state = state,
        tags = setOf(tag),
        outputData = output,
        progress = progress,
    )

    // ---- FAILED / CANCELLED ------------------------------------------

    @Test fun failedShowsWorkerErrorText() {
        val failed = info(
            WorkInfo.State.FAILED,
            output = workDataOf(LibraryScanWorker.KEY_ERROR to "disk on fire"),
        )
        assertEquals("${R.string.scan_status_failed}[disk on fire]", scanStatusFor(failed, strings))
    }

    @Test fun failedWithoutErrorDataFallsBackToUnknownError() {
        assertEquals(
            "${R.string.scan_status_failed}[${R.string.scan_status_unknown_error}]",
            scanStatusFor(info(WorkInfo.State.FAILED), strings),
        )
    }

    @Test fun cancelledShowsCancelledText() {
        assertEquals(
            "${R.string.scan_cancelled}",
            scanStatusFor(info(WorkInfo.State.CANCELLED), strings),
        )
    }

    @Test fun failedAndCancelledAreNeverNullSoTextExistenceCannotMeanSuccess() {
        // The trap: treating `status != null` as "finished". Both terminal-failure
        // states must yield text — which is exactly why callers must branch on state instead.
        assertTrue(scanStatusFor(info(WorkInfo.State.FAILED), strings) != null)
        assertTrue(scanStatusFor(info(WorkInfo.State.CANCELLED), strings) != null)
    }

    // ---- the rest of the moved logic, pinned so the extraction is provably behaviour-neutral ----

    @Test fun nullInfoHasNoStatus() {
        assertNull(scanStatusFor(null, strings))
    }

    @Test fun enqueuedShowsWorkerSpecificPreparingLabel() {
        assertEquals(
            "${R.string.scan_progress_library}",
            scanStatusFor(info(WorkInfo.State.ENQUEUED), strings),
        )
        assertEquals(
            "${R.string.scan_progress_new_arrivals}",
            scanStatusFor(info(WorkInfo.State.BLOCKED, tag = NEW_ARRIVALS_TAG), strings),
        )
    }

    @Test fun runningLibraryScanShowsCountsOnlyOnceTotalIsKnown() {
        val counted = info(
            WorkInfo.State.RUNNING,
            progress = workDataOf(LibraryScanWorker.KEY_CURRENT to 3, LibraryScanWorker.KEY_TOTAL to 9),
        )
        assertEquals("${R.string.scan_progress_counted}[3, 9]", scanStatusFor(counted, strings))

        val uncounted = info(
            WorkInfo.State.RUNNING,
            progress = workDataOf(LibraryScanWorker.KEY_CURRENT to 0, LibraryScanWorker.KEY_TOTAL to -1),
        )
        assertEquals("${R.string.scan_progress_library}", scanStatusFor(uncounted, strings))
    }

    @Test fun succeededLibraryScanReportsCounts() {
        val done = info(
            WorkInfo.State.SUCCEEDED,
            output = workDataOf(
                LibraryScanWorker.KEY_ADDED to 4,
                LibraryScanWorker.KEY_UPDATED to 2,
                LibraryScanWorker.KEY_MISSING to 1,
            ),
        )
        assertEquals("${R.string.scan_status_done}[4, 2, 1]", scanStatusFor(done, strings))
    }

    @Test fun succeededNewArrivalsDistinguishesFoundFromNone() {
        val found = info(
            WorkInfo.State.SUCCEEDED,
            tag = NEW_ARRIVALS_TAG,
            output = workDataOf(LibraryScanWorker.KEY_ADDED to 3),
        )
        assertEquals("${R.string.scan_status_new_arrivals_found}[3]", scanStatusFor(found, strings))
        assertEquals(
            "${R.string.scan_status_new_arrivals_none}",
            scanStatusFor(info(WorkInfo.State.SUCCEEDED, tag = NEW_ARRIVALS_TAG), strings),
        )
    }

    @Test fun succeededVerifyReportsNewlyMissing() {
        val missing = info(
            WorkInfo.State.SUCCEEDED,
            tag = VERIFY_TAG,
            output = workDataOf(VerifyLibraryWorker.KEY_NEWLY_MISSING to 5),
        )
        assertEquals("${R.string.scan_status_verify_missing}[5]", scanStatusFor(missing, strings))
    }

    @Test fun succeededFromUnknownWorkerHasNoStatus() {
        assertNull(scanStatusFor(info(WorkInfo.State.SUCCEEDED, tag = "some.other.Worker"), strings))
    }

    @Test fun newArrivalsTagMatchesTheWorkerClassWorkManagerUsesAsTag() {
        assertEquals(NewArrivalsScanWorker::class.java.name, NEW_ARRIVALS_TAG)
        assertEquals(LibraryScanWorker::class.java.name, LIBRARY_SCAN_TAG)
    }

    // ---- onboarding's terminal-state branching ----------------------------------------------

    @Test fun firstScanStateWaitsWhileNotFinished() {
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)) {
            assertTrue("$state", firstScanStateFor(info(state), strings) is FirstScanState.Running)
        }
    }

    @Test fun firstScanStateSucceededOnlyForSucceeded() {
        assertEquals(
            FirstScanState.Succeeded,
            firstScanStateFor(info(WorkInfo.State.SUCCEEDED), strings),
        )
    }

    @Test fun firstScanStateFailedIsNotSuccessAndCarriesWorkerError() {
        val state = firstScanStateFor(
            info(WorkInfo.State.FAILED, output = workDataOf(LibraryScanWorker.KEY_ERROR to "boom")),
            strings,
        )
        assertEquals(
            FirstScanState.Failed("${R.string.scan_status_failed}[boom]", cancelled = false),
            state,
        )
    }

    @Test fun firstScanStateCancelledIsNotSuccessAndIsFlaggedCancelled() {
        assertEquals(
            FirstScanState.Failed("${R.string.scan_cancelled}", cancelled = true),
            firstScanStateFor(info(WorkInfo.State.CANCELLED), strings),
        )
    }
}
