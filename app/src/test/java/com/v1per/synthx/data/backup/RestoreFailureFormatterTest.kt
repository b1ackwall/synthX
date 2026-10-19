package com.v1per.synthx.data.backup

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertContains

class RestoreFailureFormatterTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test fun workerFailureIncludesCauseSuppressedAndIncidentReference() {
        val root = IllegalArgumentException("deepest reason")
        val outer = IllegalStateException("outer summary", root)
        outer.addSuppressed(IllegalStateException("cleanup also failed"))
        val text = RestoreFailureFormatter.describe(outer, "incident-123")
        assertContains(text, "outer summary")
        assertContains(text, "deepest reason")
        assertContains(text, "cleanup also failed")
        assertContains(text, "incident-123")
    }
}
