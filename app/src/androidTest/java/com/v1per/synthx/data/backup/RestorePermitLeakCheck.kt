package com.v1per.synthx.data.backup

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals

/**
 * Shared cleanup for instrumented tests that use the process-wide [RestoreCoordinator].
 *
 * [assertDrainedThenReset] runs the optional [drain] steps and waits for every finite and
 * reactive permit to come back, all within [DRAIN_LIMIT_MS]. A permit still held after that
 * fails the test by name, then the coordinator is reset. An error thrown by [drain] propagates.
 *
 * A leaked permit can never be returned, so the reset cannot run after a leak; the next test that
 * calls this in its setup fails as well instead of starting from a dirty coordinator.
 */
internal object RestorePermitLeakCheck {
    const val DRAIN_LIMIT_MS = 5_000L

    fun assertDrainedThenReset(drain: suspend () -> Unit = {}) {
        runBlocking {
            withTimeoutOrNull(DRAIN_LIMIT_MS) {
                drain()
                while (RestoreCoordinator.activeOperationCountForTest() > 0) delay(10)
            }
        }
        val left = RestoreCoordinator.activeOperationCountForTest()
        val reactive = RestoreCoordinator.activeReactiveOperationCountForTest()
        assertEquals(
            "$left restore permit(s) ($reactive reactive) still held $DRAIN_LIMIT_MS ms after the " +
                "test: an operation never released its RestoreCoordinator permit",
            0,
            left,
        )
        RestoreCoordinator.resetForTest()
    }
}
