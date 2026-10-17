package com.v1per.synthx.data.repository

import com.v1per.synthx.data.repository.RepositoryDbFlow.retryOnFailure
import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** P1-T6 (R1-F10): [RepositoryDbFlow.retryOnFailure] resubscribes an upstream flow that fails
 *  instead of letting the failure terminate it — see SongRepositoryImpl's shared flows. */
class RepositoryDbFlowRetryTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test
    fun `one transient failure followed by success delivers the successful value`() = runTest {
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts == 1) throw IllegalStateException("transient boom")
            emit(42)
        }

        val result = source.retryOnFailure("TAG", "testFlow").first()

        assertEquals(42, result)
        assertEquals(2, attempts)
    }

    @Test
    fun `cancellation exception is never retried and propagates immediately`() = runTest {
        var attempts = 0
        val source = flow<Int> {
            attempts++
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            source.retryOnFailure("TAG", "testFlow").first()
        }
        // A single attempt only: the predicate rejected the retry outright, no backoff/log path taken.
        assertEquals(1, attempts)
    }

    @Test
    fun `repeated failures keep retrying rather than giving up after a few attempts`() = runTest {
        var attempts = 0
        val source = flow {
            attempts++
            if (attempts <= 5) throw RuntimeException("boom #$attempts")
            emit(99)
        }

        val result = source.retryOnFailure("TAG", "testFlow").first()

        assertEquals(99, result)
        assertEquals(6, attempts)
    }
}
