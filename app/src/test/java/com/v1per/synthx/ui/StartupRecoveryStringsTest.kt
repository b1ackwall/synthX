package com.v1per.synthx.ui

import com.v1per.synthx.testing.ProductionSource
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup recovery surfaces render only resource text. A literal passed to Text, assigned to
 * the error state or handed to the share chooser would bypass strings.xml and fork the copy the
 * two restart-required surfaces share.
 */
class StartupRecoveryStringsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val files = listOf(
        "com/v1per/synthx/DatabaseStartupContent.kt",
        "com/v1per/synthx/ui/RestoreShellSurfaces.kt",
    )

    private val literalText = Regex("""\bText\(\s*(text\s*=\s*)?(if\b[^\n]*)?"""")
    private val literalError = Regex("""\b(actionError\s*=|createChooser\([^\n]*,)\s*"""")
    private val textCall = Regex("""\bText\(""")

    @Test fun noStringLiteralReachesTheScreenOutsideResources() {
        val offenders = files.flatMap { path ->
            val code = ProductionSource.code(path)
            (literalText.findAll(code) + literalError.findAll(code)).map { "$path: ${it.value.trim()}" }.toList()
        }

        assertEquals(emptyList(), offenders, "startup recovery text must come from string resources")
    }

    @Test fun theScanSeesTheTextCallsOfBothFiles() {
        val calls = files.sumOf { textCall.findAll(ProductionSource.code(it)).count() }

        assertTrue(calls >= 20, "expected the Text calls of both files, found $calls")
    }
}
