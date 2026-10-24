package com.v1per.synthx.ui.common

import androidx.work.Data
import androidx.work.WorkInfo
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.KnownAudioRoot
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.data.scanner.ScanDegradation
import com.v1per.synthx.data.scanner.ScanDegradationReason
import com.v1per.synthx.data.scanner.VerifyLibraryWorker
import com.v1per.synthx.data.scanner.putScanDegradations
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Each count-bearing scan status picks its plural by the count it shows, for 0, 1 and many. */
class ScanStatusQuantityTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val strings = object : ScanStatusStrings {
        override fun get(id: Int, vararg args: Any) = if (args.isEmpty()) "$id" else "$id${args.toList()}"

        override fun getQuantity(id: Int, quantity: Int, vararg args: Any) = "$id#$quantity${args.toList()}"
    }

    private val degradation = ScanDegradation(KnownAudioRoot.Music, ScanDegradationReason.RootUnreachable)
    private val warning =
        "${R.string.scan_degraded_root_unreachable}[${R.string.scan_root_music}]"

    private fun succeeded(tag: String, output: Data) = WorkInfo(
        id = UUID.randomUUID(),
        state = WorkInfo.State.SUCCEEDED,
        tags = setOf(tag),
        outputData = output,
        progress = Data.EMPTY,
    )

    private fun newArrivals(added: Int, degraded: Boolean = false) = succeeded(
        NEW_ARRIVALS_TAG,
        Data.Builder()
            .putInt(LibraryScanWorker.KEY_ADDED, added)
            .putScanDegradations(if (degraded) listOf(degradation) else emptyList())
            .build(),
    )

    private fun verify(newlyMissing: Int, degraded: Boolean = false) = succeeded(
        VERIFY_TAG,
        Data.Builder()
            .putInt(VerifyLibraryWorker.KEY_NEWLY_MISSING, newlyMissing)
            .putScanDegradations(if (degraded) listOf(degradation) else emptyList())
            .build(),
    )

    private fun foundId() = R.plurals.scan_status_new_arrivals_found_count
    private fun foundDegradedId() = R.plurals.scan_status_new_arrivals_degraded_count
    private fun missingId() = R.plurals.scan_status_verify_missing_count
    private fun missingDegradedId() = R.plurals.scan_status_verify_degraded_count

    @Test fun newArrivalsFoundUsesTheAddedCountAsQuantityForOne() =
        assertEquals("${foundId()}#1[1]", scanStatusFor(newArrivals(1), strings))

    @Test fun newArrivalsFoundUsesTheAddedCountAsQuantityForMany() =
        assertEquals("${foundId()}#1000[1000]", scanStatusFor(newArrivals(1000), strings))

    @Test fun newArrivalsWithNothingAddedShowsTheNoneTextNotAPlural() =
        assertEquals("${R.string.scan_status_new_arrivals_none}", scanStatusFor(newArrivals(0), strings))

    @Test fun newArrivalsDegradedUsesTheAddedCountAsQuantityForZero() =
        assertEquals("${foundDegradedId()}#0[0, $warning]", scanStatusFor(newArrivals(0, degraded = true), strings))

    @Test fun newArrivalsDegradedUsesTheAddedCountAsQuantityForOne() =
        assertEquals("${foundDegradedId()}#1[1, $warning]", scanStatusFor(newArrivals(1, degraded = true), strings))

    @Test fun newArrivalsDegradedUsesTheAddedCountAsQuantityForMany() =
        assertEquals("${foundDegradedId()}#11[11, $warning]", scanStatusFor(newArrivals(11, degraded = true), strings))

    @Test fun verifyMissingUsesTheNewlyMissingCountAsQuantityForOne() =
        assertEquals("${missingId()}#1[1]", scanStatusFor(verify(1), strings))

    @Test fun verifyMissingUsesTheNewlyMissingCountAsQuantityForMany() =
        assertEquals("${missingId()}#1000[1000]", scanStatusFor(verify(1000), strings))

    @Test fun verifyWithNothingMissingShowsTheNoneTextNotAPlural() =
        assertEquals("${R.string.scan_status_verify_none}", scanStatusFor(verify(0), strings))

    @Test fun verifyDegradedUsesTheNewlyMissingCountAsQuantityForZero() =
        assertEquals("${missingDegradedId()}#0[0, $warning]", scanStatusFor(verify(0, degraded = true), strings))

    @Test fun verifyDegradedUsesTheNewlyMissingCountAsQuantityForOne() =
        assertEquals("${missingDegradedId()}#1[1, $warning]", scanStatusFor(verify(1, degraded = true), strings))

    @Test fun verifyDegradedUsesTheNewlyMissingCountAsQuantityForMany() =
        assertEquals("${missingDegradedId()}#2[2, $warning]", scanStatusFor(verify(2, degraded = true), strings))
}
