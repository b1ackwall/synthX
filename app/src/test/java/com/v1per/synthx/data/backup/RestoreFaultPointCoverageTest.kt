package com.v1per.synthx.data.backup

import com.v1per.synthx.ui.testing.TestTimeouts
import java.io.File
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every restore fault point is injected by name in at least one device test, so a newly added
 * point cannot sit untested. Which test proves each point is listed in
 * docs/code-work/Safety-Net/slice2-implementation.md (proof table).
 */
class RestoreFaultPointCoverageTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun everyFaultPointIsInjectedByNameInADeviceTest() {
        val deviceTests = File("src/androidTest/java")
        assertTrue(deviceTests.isDirectory, "device test sources not found from ${File(".").absolutePath}")
        val sources = deviceTests.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        val missing = RestoreFaultPoint.entries.filterNot { point ->
            Regex("""RestoreFaultPoint\.${point.name}\b""").containsMatchIn(sources)
        }

        assertEquals(emptyList(), missing, "fault points no device test names")
    }
}
