package com.v1per.synthx.ui.screens.starred

import com.v1per.synthx.testing.ProductionSource
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Starred info dialog quotes the "Remove from Starred" menu row. The quote is a format argument the
 * dialog fills from the row's own string, so renaming the row cannot leave the dialog quoting a stale label.
 */
class StarredInfoStringsTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val valuesDir: File = listOf(File("src/main/res/values"), File("app/src/main/res/values"))
        .firstOrNull(File::isDirectory) ?: error("Could not locate the values resource folder")

    private val strings: Map<String, String> by lazy {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val elements = builder.parse(File(valuesDir, "strings.xml")).documentElement.getElementsByTagName("string")
        (0 until elements.length).map { elements.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun theGraduateParagraphQuotesTheMenuRowThroughItsOnlyPlaceholder() {
        val paragraph = strings.getValue("starred_info_dialog_message_graduate")
        val label = strings.getValue("actions_remove_from_starred")

        assertEquals(1, Regex("%\\d+\\\$s").findAll(paragraph).count(), "exactly one placeholder in: $paragraph")
        assertTrue("“%1\$s”" in paragraph, "the placeholder sits inside typographic quotes: $paragraph")
        assertFalse(label in paragraph, "the label is quoted through the placeholder, not copied: $paragraph")
        assertTrue("“$label”" in paragraph.format(label), "the filled paragraph quotes the menu row")
    }

    @Test fun theDialogFillsThePlaceholderFromTheMenuRowsString() {
        val dialog = ProductionSource.code("com/v1per/synthx/ui/screens/starred/StarredInfoDialog.kt")
        val filled = Regex(
            """stringResource\(\s*R\.string\.starred_info_dialog_message_graduate\s*,\s*""" +
                """stringResource\(\s*R\.string\.actions_remove_from_starred\s*\)""",
        )

        assertTrue(filled.containsMatchIn(dialog), "StarredInfoDialog formats the paragraph with the menu row's label")
    }
}
