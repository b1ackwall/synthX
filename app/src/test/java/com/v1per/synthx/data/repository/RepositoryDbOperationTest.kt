package com.v1per.synthx.data.repository

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

class RepositoryDbOperationTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @AfterTest fun reset() = RestoreCoordinator.resetForTest()

    @Test fun finiteDeveloperActionRunsNormally() = runTest {
        var mutations = 0
        RepositoryDbOperation.run { mutations++ }
        assertEquals(1, mutations)
    }

    @Test fun existingDeveloperActionDrainsBeforeRestore() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val action = launch {
            RepositoryDbOperation.run {
                started.complete(Unit)
                finish.await()
            }
        }
        started.await()
        val drained = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive {
                RestoreCoordinator.awaitDrained()
                drained.complete(Unit)
            }
        }
        yield()
        assertFalse(drained.isCompleted)
        finish.complete(Unit)
        action.join()
        restore.await()
    }

    @Test fun preparationRejectsDeveloperActionBeforeDao() = runTest {
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val restore = launch {
            RestoreCoordinator.exclusive {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()
        assertFailsWith<IllegalStateException> { RepositoryDbOperation.run { error("must not run") } }
        hold.complete(Unit)
        restore.join()
    }

    @Test fun closedRejectsDeveloperActionBeforeDao() = runTest {
        RestoreCoordinator.markClosed()
        assertFailsWith<IllegalStateException> { RepositoryDbOperation.run { error("must not run") } }
    }
}
