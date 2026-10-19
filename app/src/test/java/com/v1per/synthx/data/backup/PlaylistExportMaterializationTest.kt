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

class PlaylistExportMaterializationTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @AfterTest fun reset() = RestoreCoordinator.resetForTest()

    @Test fun admittedMaterializationDrainsBeforeRestore() = runTest {
        val queryStarted = CompletableDeferred<Unit>()
        val finishQuery = CompletableDeferred<Unit>()
        val materialization = launch {
            PlaylistExportMaterialization.run {
                queryStarted.complete(Unit)
                finishQuery.await()
            }
        }
        queryStarted.await()
        val drained = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive {
                RestoreCoordinator.awaitDrained()
                drained.complete(Unit)
            }
        }
        yield()
        assertFalse(drained.isCompleted)
        finishQuery.complete(Unit)
        materialization.join()
        restore.await()
        assertTrue(drained.isCompleted)
    }

    @Test fun permitReleasesBeforePostMaterializationSafWrite() = runTest {
        val export = PlaylistExportMaterialization.run { "materialized" }
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
        RestoreCoordinator.exclusive {
            RestoreCoordinator.awaitDrained()
            assertEquals("materialized", export)
        }
    }

    @Test fun preparationRejectsBeforeRoomQuery() = runTest {
        val hold = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val restore = launch {
            RestoreCoordinator.exclusive {
                entered.complete(Unit)
                hold.await()
            }
        }
        entered.await()
        var queries = 0
        assertFailsWith<IllegalStateException> {
            PlaylistExportMaterialization.run { queries++ }
        }
        assertEquals(0, queries)
        hold.complete(Unit)
        restore.join()
    }

    @Test fun closedRejectsBeforeRoomQuery() = runTest {
        RestoreCoordinator.markClosed()
        assertFailsWith<IllegalStateException> {
            PlaylistExportMaterialization.run { error("must not query") }
        }
    }
}
