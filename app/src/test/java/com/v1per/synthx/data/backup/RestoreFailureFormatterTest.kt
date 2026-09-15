package com.v1per.synthx.data.backup

import kotlin.test.Test
import kotlin.test.assertContains

class RestoreFailureFormatterTest {
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
