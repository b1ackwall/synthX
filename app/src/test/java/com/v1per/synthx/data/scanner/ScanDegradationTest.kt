package com.v1per.synthx.data.scanner

import androidx.work.Data
import androidx.work.workDataOf
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanDegradationTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val degradation = ScanDegradation(
        KnownAudioRoot.Music,
        ScanDegradationReason.WalkIncomplete,
    )
    private val encodedDegradation = "${degradation.root.name}:${degradation.reason.name}"

    @Test fun libraryOutputCarriesDegradationDistinctFromPlainSuccessAndFailure() {
        val plain = scanOutputData(ScanResult(1, 2, 0, 3))
        val degraded = scanOutputData(
            ScanResult(1, 2, 0, 3, degradations = setOf(degradation)),
        )
        val failure = workDataOf(LibraryScanWorker.KEY_ERROR to "Scan failed")

        assertTrue(plain.scanDegradations().isEmpty())
        assertEquals(setOf(degradation), degraded.scanDegradations())
        assertEquals(1, degraded.getInt(LibraryScanWorker.KEY_ADDED, -1))
        assertFalse(failure.getString(LibraryScanWorker.KEY_ERROR).isNullOrEmpty())
        assertTrue(failure.scanDegradations().isEmpty())
    }

    @Test fun newArrivalsAndVerifyOutputsAlsoCarryRootAwareDegradation() {
        val arrivalsDegradation = ScanDegradation(
            KnownAudioRoot.NewArrivals,
            ScanDegradationReason.RootUnreachable,
        )
        val arrivals = scanOutputData(
            ScanResult(
                0,
                0,
                0,
                0,
                missingReconciled = false,
                degradations = setOf(arrivalsDegradation),
            ),
            includeMissing = false,
        )
        val verify = verifyOutputData(VerifyResult(0, setOf(degradation)))

        assertEquals(setOf(arrivalsDegradation), arrivals.scanDegradations())
        assertEquals(setOf(degradation), verify.scanDegradations())
    }

    @Test fun nullElementBesideAValidEntryIsSkipped() {
        val data = rawDegradations(encodedDegradation, null)

        assertEquals(setOf(degradation), data.scanDegradations())
    }

    @Test fun onlyNullElementsGiveNoDegradation() {
        val data = rawDegradations(null, null)

        assertTrue(data.scanDegradations().isEmpty())
    }

    @Test fun nullElementIsStillSkippedAfterAByteArrayRoundTrip() {
        val restored = Data.fromByteArray(rawDegradations(encodedDegradation, null).toByteArray())

        assertEquals(
            listOf(encodedDegradation, null),
            restored.getNullableStringArray(KEY_SCAN_DEGRADATIONS)?.toList(),
        )
        assertEquals(setOf(degradation), restored.scanDegradations())
    }

    // Writes the array directly, since putScanDegradations never writes a null element.
    private fun rawDegradations(vararg entries: String?): Data =
        Data.Builder().putStringArray(KEY_SCAN_DEGRADATIONS, arrayOf(*entries)).build()
}
