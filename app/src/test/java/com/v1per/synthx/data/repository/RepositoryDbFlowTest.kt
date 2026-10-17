package com.v1per.synthx.data.repository

import com.v1per.synthx.data.backup.RestoreCoordinator
import com.v1per.synthx.ui.testing.TestTimeouts
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryDbFlowTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @AfterTest
    fun reset() = RestoreCoordinator.resetForTest()

    @Test
    fun `external cold upstream terminates before final drain and never deadlocks`() = runTest {
        val started = CompletableDeferred<Unit>()
        val terminated = CompletableDeferred<Unit>()
        val collector = launch {
            RepositoryDbFlow.observe {
                flow<Int> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        terminated.complete(Unit)
                    }
                }
            }.collect()
        }
        started.await()

        val drainFinished = CompletableDeferred<Unit>()
        val restore = async {
            RestoreCoordinator.exclusive {
                assertFalse(drainFinished.isCompleted)
                RepositoryDbFlow.quiesceForRestore()
                assertTrue(terminated.isCompleted)
                RestoreCoordinator.awaitDrained()
                drainFinished.complete(Unit)
            }
        }

        withTimeout(1_000) { restore.await() }
        assertTrue(drainFinished.isCompleted)
        assertTrue(collector.isCancelled)
    }

    @Test
    fun `closed admission waits before DAO Flow construction`() = runTest {
        val daoStarts = AtomicInteger()
        RestoreCoordinator.exclusive {
            val job = launch {
                RepositoryDbFlow.observe {
                    daoStarts.incrementAndGet()
                    flow<Int> { error("must not collect") }
                }.collect()
            }
            yield()
            assertEquals(0, daoStarts.get())
            assertFalse(job.isCompleted)
            job.cancel()
            job.join()
        }
    }

    @Test
    fun `pre-close abort lets waiting reactive collector recover`() = runTest {
        val daoStarts = AtomicInteger()
        val collected = CompletableDeferred<Int>()
        val restoreEntered = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        val restore = launch {
            RestoreCoordinator.exclusive {
                restoreEntered.complete(Unit)
                releaseRestore.await()
            }
        }
        restoreEntered.await()
        val collector = launch {
            RepositoryDbFlow.observe {
                daoStarts.incrementAndGet()
                flow { emit(7) }
            }.collect { collected.complete(it) }
        }
        yield()
        assertEquals(0, daoStarts.get())

        releaseRestore.complete(Unit)
        restore.join()
        RestoreCoordinator.markNormal()

        assertEquals(7, withTimeout(1_000) { collected.await() })
        collector.join()
        assertEquals(1, daoStarts.get())
    }

    @Test
    fun `multiple active upstreams all terminate before drain`() = runTest {
        val started = List(3) { CompletableDeferred<Unit>() }
        val terminated = AtomicInteger()
        val collectors = started.map { signal ->
            launch {
                RepositoryDbFlow.observe {
                    flow<Int> {
                        signal.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            terminated.incrementAndGet()
                        }
                    }
                }.collect()
            }
        }
        started.forEach { it.await() }
        assertEquals(3, RestoreCoordinator.activeReactiveOperationCountForTest())

        withTimeout(1_000) {
            RestoreCoordinator.exclusive {
                RepositoryDbFlow.quiesceForRestore()
                RestoreCoordinator.awaitDrained()
            }
        }
        collectors.forEach { it.join() }
        assertEquals(3, terminated.get())
        assertEquals(0, RestoreCoordinator.activeOperationCountForTest())
    }

    @Test
    fun `downstream reconnect attempt cannot restart upstream while closed`() = runTest {
        val starts = AtomicInteger()
        val firstStart = CompletableDeferred<Unit>()
        val collector = launch {
            while (true) {
                try {
                    RepositoryDbFlow.observe {
                        starts.incrementAndGet()
                        flow<Int> {
                            firstStart.complete(Unit)
                            awaitCancellation()
                        }
                    }.collect()
                } catch (error: CancellationException) {
                    // Deliberately hostile downstream: attempt one recollection despite cancellation.
                    runCatching {
                        RepositoryDbFlow.observe {
                            starts.incrementAndGet()
                            flow<Int> { emit(1) }
                        }.collect()
                    }
                    throw error
                }
            }
        }
        firstStart.await()

        RestoreCoordinator.exclusive {
            RepositoryDbFlow.quiesceForRestore()
            RestoreCoordinator.awaitDrained()
        }
        collector.join()
        assertEquals(1, starts.get())
    }

    @Test
    fun `finite drain can complete before permanent reactive quiescence`() = runTest {
        val started = CompletableDeferred<Unit>()
        val collector = launch {
            RepositoryDbFlow.observe {
                flow<Int> { started.complete(Unit); awaitCancellation() }
            }.collect()
        }
        started.await()

        RestoreCoordinator.exclusive {
            withTimeout(1_000) { RestoreCoordinator.awaitFiniteDrained() }
            assertEquals(1, RestoreCoordinator.activeReactiveOperationCountForTest())
            RepositoryDbFlow.quiesceForRestore()
            RestoreCoordinator.awaitDrained()
        }
        collector.join()
    }

    @Test
    fun `terminal close cannot construct or reacquire reactive upstream`() = runTest {
        val starts = AtomicInteger()
        RestoreCoordinator.markClosed()
        assertFailsWith<IllegalStateException> {
            RepositoryDbFlow.observe {
                starts.incrementAndGet()
                flow<Int> { emit(1) }
            }.collect()
        }
        yield()
        assertEquals(0, starts.get())
    }
}
