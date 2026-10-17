package com.v1per.synthx.data.scanner

import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderScannerAdmissionTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @AfterTest fun reset() = RestoreCoordinator.resetForTest()

    @Test fun normalDirectScanExecutesMutatingBody() = runTest {
        var mutations = 0
        FolderScannerAdmission.direct { mutations++ }
        assertEquals(1, mutations)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun activeDirectScanDrainsBeforeRestoreProceeds() = runTest {
        val scanStarted = CompletableDeferred<Unit>()
        val finishScan = CompletableDeferred<Unit>()
        val scan = launch {
            FolderScannerAdmission.direct {
                scanStarted.complete(Unit)
                finishScan.await()
            }
        }
        scanStarted.await()
        val restorePassedDrain = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive {
                RestoreCoordinator.awaitDrained()
                restorePassedDrain.complete(Unit)
            }
        }
        yield()
        assertFalse(restorePassedDrain.isCompleted)
        finishScan.complete(Unit)
        scan.join()
        restore.await()
        assertTrue(restorePassedDrain.isCompleted)
    }

    @Test fun directScanDuringPreparationIsRejectedBeforeMutation() = runTest {
        val holdRestore = CompletableDeferred<Unit>()
        val preparing = CompletableDeferred<Unit>()
        val restore = launch {
            RestoreCoordinator.exclusive {
                preparing.complete(Unit)
                holdRestore.await()
            }
        }
        preparing.await()
        var mutations = 0
        assertFailsWith<IllegalStateException> {
            FolderScannerAdmission.direct { mutations++ }
        }
        assertEquals(0, mutations)
        holdRestore.complete(Unit)
        restore.join()
    }

    @Test fun directScanDuringClosedIsRejected() = runTest {
        RestoreCoordinator.markClosed()
        assertFailsWith<IllegalStateException> {
            FolderScannerAdmission.direct { error("must not execute") }
        }
    }

    @Test fun admittedInternalPathDoesNotAcquireTwiceOrDeadlock() = runTest {
        FolderScannerAdmission.direct {
            assertEquals(1, RestoreCoordinator.activeOperationCountForTest())
            FolderScannerAdmission.alreadyAdmitted {
                assertEquals(1, RestoreCoordinator.activeOperationCountForTest())
            }
        }
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }
}
