package com.v1per.synthx.ui

import com.v1per.synthx.testing.ProductionSource
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dialogs, snackbars and buttons all go through the shared components. A Material AlertDialog called anywhere
 * else would fall back to Material's container and its own button flow, a bare SnackbarHost to Material's
 * inverse colours, a direct showSnackbar call would skip the replace rule of showSynthXSnackbar, and a Material
 * TextButton, Button or OutlinedButton would take the theme's Accent primary.
 */
class SharedControlUsageTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val sources = ProductionSource.allCode()

    /** The production files, other than [allowed], whose code calls [call]. */
    private fun callersOf(call: Regex, allowed: String): List<String> =
        sources.filter { (path, code) -> !path.endsWith(allowed) && call.containsMatchIn(code) }.keys.sorted()

    @Test fun onlyTheSharedWrapperCallsAlertDialog() {
        val dialogCall = Regex("""(?<![\w.])AlertDialog\(""")
        assertEquals(emptyList(), callersOf(dialogCall, "ui/components/SynthXDialog.kt"))
    }

    @Test fun onlyTheSharedWrapperCallsSnackbarHost() {
        val hostCall = Regex("""(?<![\w.])SnackbarHost\(""")
        assertEquals(emptyList(), callersOf(hostCall, "ui/components/SynthXSnackbar.kt"))
    }

    /** Every message goes through showSynthXSnackbar, so the replace rule holds on every host. */
    @Test fun onlyTheSharedHelperCallsShowSnackbar() {
        val showCall = Regex("""\bshowSnackbar\(""")
        assertEquals(emptyList(), callersOf(showCall, "ui/components/SynthXSnackbar.kt"))
    }

    @Test fun noMaterialTextOutlinedOrFilledButtonRemains() {
        val names = "TextButton|Button|OutlinedButton|FilledTonalButton|ElevatedButton"
        val materialButton = Regex(
            """import androidx\.compose\.material3\.($names)\b|androidx\.compose\.material3\.($names)\(|(?<![\w.])($names)\(""",
        )
        assertEquals(emptyList(), sources.filterValues { materialButton.containsMatchIn(it) }.keys.sorted())
    }

    @Test fun theScanSeesTheWrapperItself() {
        val wrapper = sources.entries.single { it.key.endsWith("ui/components/SynthXDialog.kt") }.value
        assertTrue(Regex("""(?<![\w.])AlertDialog\(""").containsMatchIn(wrapper), "the wrapper calls AlertDialog")
        val snackbar = sources.entries.single { it.key.endsWith("ui/components/SynthXSnackbar.kt") }.value
        assertTrue(Regex("""(?<![\w.])SnackbarHost\(""").containsMatchIn(snackbar), "the wrapper calls SnackbarHost")
        assertTrue(Regex("""\bshowSnackbar\(""").containsMatchIn(snackbar), "the helper calls showSnackbar")
    }
}
