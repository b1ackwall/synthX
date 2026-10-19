package com.v1per.synthx.ui.testing

import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * Shared per-test time limit for every JVM test class. A test that runs past it fails by name
 * with TestTimedOutException instead of stalling the whole Gradle run.
 *
 * Usage, as the first member of a test class:
 * `@get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()`
 */
object TestTimeouts {
    /** Lowest rule order, so the limit wraps every other rule plus `@Before` and `@After`. */
    const val RULE_ORDER = Int.MIN_VALUE

    /** 30 s default; the slowest JVM test takes under 2 s. Pass a larger value only with a reason. */
    const val JVM_SECONDS = 30L

    fun jvm(seconds: Long = JVM_SECONDS): Timeout = Timeout.builder().withTimeout(seconds, TimeUnit.SECONDS).build()
}
