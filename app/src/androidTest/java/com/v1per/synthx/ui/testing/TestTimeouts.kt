package com.v1per.synthx.ui.testing

import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Shared per-test time limit for every instrumented test class. A test that runs past it fails
 * by name with TestTimedOutException, also when the hang is inside `@After` (the runner's
 * `timeout_msec` argument only covers the test method itself).
 *
 * Usage, as the first member of a test class:
 * `@get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()`
 */
object TestTimeouts {
    /** Lowest rule order, so the limit wraps every other rule plus `@Before` and `@After`. */
    const val RULE_ORDER = Int.MIN_VALUE

    /** 60 s default; the slowest device test takes about 14 s. Pass a larger value only with a reason. */
    const val DEVICE_SECONDS = 60L

    fun device(seconds: Long = DEVICE_SECONDS): Timeout =
        Timeout.builder().withTimeout(seconds, TimeUnit.SECONDS).build()
}
