package com.v1per.synthx.data.backup

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

class WorkerDatabaseAccessTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @AfterTest fun reset() = RestoreCoordinator.resetForTest()

    @Test fun normalWorkerMayCreateAndUseDatabaseOwner() = runTest {
        var daoTouches = 0
        WorkerDatabaseAccess.run(awaitStartup = {}) { daoTouches++ }
        assertEquals(1, daoTouches)
    }

    @Test fun preparationRejectsBeforeRoomFactory() = runTest {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val restore = launch {
            RestoreCoordinator.exclusive {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()
        var roomFactories = 0
        assertFailsWith<IllegalStateException> {
            WorkerDatabaseAccess.run(awaitStartup = {}) { roomFactories++ }
        }
        assertEquals(0, roomFactories)
        hold.complete(Unit)
        restore.join()
    }

    @Test fun closedRejectsBeforeRoomFactory() = runTest {
        RestoreCoordinator.markClosed()
        var roomFactories = 0
        assertFailsWith<IllegalStateException> {
            WorkerDatabaseAccess.run(awaitStartup = {}) { roomFactories++ }
        }
        assertEquals(0, roomFactories)
    }

    @Test fun unresolvedStartupGateBlocksBeforeAdmissionAndRoom() = runTest {
        val startup = CompletableDeferred<Unit>()
        var roomFactories = 0
        val worker = async {
            WorkerDatabaseAccess.run(awaitStartup = { startup.await() }) { roomFactories++ }
        }
        yield()
        assertFalse(worker.isCompleted)
        assertEquals(0, roomFactories)
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
        startup.complete(Unit)
        worker.await()
        assertEquals(1, roomFactories)
    }

    @Test fun startupGateResolvingNormalAllowsWorker() = runTest {
        var touched = false
        WorkerDatabaseAccess.run(
            awaitStartup = { RestoreCoordinator.requireNormal() },
            block = { touched = true },
        )
        assertTrue(touched)
    }

    @Test fun startupEmergencyOrTerminalNeverRunsRoomFactory() = runTest {
        for (terminal in listOf(RestoreCoordinator.Mode.EMERGENCY, RestoreCoordinator.Mode.RESTART_REQUIRED)) {
            when (terminal) {
                RestoreCoordinator.Mode.EMERGENCY -> RestoreCoordinator.markEmergency()
                RestoreCoordinator.Mode.RESTART_REQUIRED -> RestoreCoordinator.markRestartRequired(false)
                else -> error("unexpected")
            }
            var roomFactories = 0
            assertFailsWith<IllegalStateException> {
                WorkerDatabaseAccess.run(
                    awaitStartup = { RestoreCoordinator.requireNormal() },
                    block = { roomFactories++ },
                )
            }
            assertEquals(0, roomFactories)
            RestoreCoordinator.resetForTest()
        }
    }
}
