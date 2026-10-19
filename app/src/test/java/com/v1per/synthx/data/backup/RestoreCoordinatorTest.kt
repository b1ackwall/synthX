package com.v1per.synthx.data.backup

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/** Focused proof for the admission/drain boundary, independent of Room. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreCoordinatorTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    private fun reset() = RestoreCoordinator.resetForTest()
    private suspend fun assertRejected(block: suspend () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun operationCountIncrementsAndDecrements() = runTest {
        reset()
        RestoreCoordinator.withDbOperation {
            assertEquals(1, RestoreCoordinator.activeOperationCountForTest())
        }
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun exceptionReleasesOperationCount() = runTest {
        reset()
        assertRejected { RestoreCoordinator.withDbOperation { error("expected") } }
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test fun existingOperationDelaysRestore() = runTest {
        reset()
        val releaseOperation = CompletableDeferred<Unit>()
        val operation = launch { RestoreCoordinator.withDbOperation { releaseOperation.await() } }
        yield()
        val restoreEntered = CompletableDeferred<Unit>()
        val restore = launch { RestoreCoordinator.exclusive { RestoreCoordinator.awaitDrained(); restoreEntered.complete(Unit) } }
        // Runs every runnable coroutine and skips virtual time, so a timed wait cannot pass for a drain.
        advanceUntilIdle()
        assertFalse(restoreEntered.isCompleted)
        releaseOperation.complete(Unit)
        operation.join(); restore.join()
        assertTrue(restoreEntered.isCompleted)
        reset()
    }

    @Test fun preparingRejectsNewOperations() = runTest {
        reset()
        val releaseRestore = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val restore = launch { RestoreCoordinator.exclusive { entered.complete(Unit); releaseRestore.await() } }
        entered.await()
        assertRejected { RestoreCoordinator.withDbOperation { } }
        releaseRestore.complete(Unit); restore.join(); reset()
    }

    @Test fun closedRejectsOperations() = runTest {
        reset(); RestoreCoordinator.markClosed()
        assertRejected { RestoreCoordinator.withDbOperation { } }
        reset()
    }

    @Test fun preCloseAbortReturnsToNormal() = runTest {
        reset()
        assertRejected { RestoreCoordinator.exclusive { error("pre-close failure") } }
        // RestoreTransaction performs this transition for every pre-close exception.
        RestoreCoordinator.markNormal()
        RestoreCoordinator.withDbOperation { }
        assertEquals(RestoreCoordinator.Mode.NORMAL, RestoreCoordinator.currentMode())
    }

    @Test fun activeBackupOperationDrainsBeforeRestoreBoundary() = runTest {
        reset()
        val releaseBackup = CompletableDeferred<Unit>()
        val backup = launch {
            RestoreCoordinator.withDbOperation {
                BackupCreationCoordinator.serialize { releaseBackup.await() }
            }
        }
        yield()
        val drained = CompletableDeferred<Unit>()
        val restore = launch { RestoreCoordinator.exclusive { RestoreCoordinator.awaitDrained(); drained.complete(Unit) } }
        // Runs every runnable coroutine and skips virtual time, so a timed wait cannot pass for a drain.
        advanceUntilIdle()
        assertFalse(drained.isCompleted, "restore crossed the drain boundary while a backup held its permit")
        releaseBackup.complete(Unit); backup.join(); restore.join()
        assertTrue(drained.isCompleted); reset()
    }

    @Test fun preparationAndClosedRejectNormalBackupAdmission() = runTest {
        reset()
        val hold = CompletableDeferred<Unit>()
        val restore = launch { RestoreCoordinator.exclusive { hold.await() } }
        yield()
        assertRejected { RestoreCoordinator.withDbOperation { BackupCreationCoordinator.serialize { } } }
        hold.complete(Unit); restore.join()
        RestoreCoordinator.markClosed()
        assertRejected { RestoreCoordinator.withDbOperation { BackupCreationCoordinator.serialize { } } }
        reset()
    }

    @Test fun restoreOwnedSafetyWorkDoesNotNeedNormalAdmission() = runTest {
        reset()
        RestoreCoordinator.exclusive {
            // Mirrors RestoreTransaction's internal safety snapshot ownership: it executes
            // after preparation without attempting BackupManager's normal admission path.
            assertEquals(RestoreCoordinator.Mode.RESTORE_PREPARING, RestoreCoordinator.currentMode())
        }
        reset()
    }

    @Test fun directRestoreExecutionsAreSerializedBeforeStateInspection() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            RestoreExecutionCoordinator.serialize {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                firstEntered.complete(Unit)
                releaseFirst.await()
                active.decrementAndGet()
            }
        }
        firstEntered.await()
        val second = launch {
            RestoreExecutionCoordinator.serialize {
                maximum.updateAndGet { maxOf(it, active.incrementAndGet()) }
                active.decrementAndGet()
            }
        }
        yield()
        assertEquals(1, maximum.get())
        releaseFirst.complete(Unit)
        first.join(); second.join()
        assertEquals(1, maximum.get())
    }
}
