package com.v1per.synthx.data

import com.v1per.synthx.ui.testing.TestTimeouts
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [collectCatching] is what gives ExoPlayerEngine's remap/deletion collectors their per-event
 * exception boundary (P4-T2 / R3-F07). A plain `collect { action(it) }` loop has the whole
 * handler inside the collector coroutine's body — an uncaught exception from handling ONE event
 * kills that coroutine permanently, silently dropping every event of that type for the rest of
 * the process. This proves the boundary actually prevents that: a handler that throws on its
 * first call must not stop a second event from still reaching the handler.
 */
class SafeCollectTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun aThrowingHandlerDoesNotStopSubsequentEvents() = runTest {
        val source = flow {
            emit(1)
            emit(2)
            emit(3)
        }
        val processed = mutableListOf<Int>()
        val errors = mutableListOf<Int>()

        source.collectCatching(
            onError = { value, _ -> errors.add(value) },
        ) { value ->
            if (value == 2) error("simulated handler failure for $value")
            processed.add(value)
        }

        assertEquals(listOf(1, 3), processed, "events after the failing one must still be handled")
        assertEquals(listOf(2), errors)
    }

    @Test fun cancellationIsNeverSwallowed() = runTest {
        val source = flow { emit(Unit) }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            source.collectCatching(onError = { _, _ -> }) {
                throw kotlinx.coroutines.CancellationException("structured concurrency must win")
            }
        }
    }
}

/**
 * MUTATION-TEST RECORD (P4 report): [collectCatching]'s per-event try/catch was temporarily
 * replaced with a plain `collect { action(it) }` (no boundary at all) and the test suite re-run —
 * [SafeCollectTest.aThrowingHandlerDoesNotStopSubsequentEvents] failed as expected (the thrown
 * exception from handling `2` propagated out of `collect` entirely, so `3` was never delivered).
 * The real implementation was restored immediately after confirming the failure.
 */
