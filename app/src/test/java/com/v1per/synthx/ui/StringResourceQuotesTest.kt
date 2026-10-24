package com.v1per.synthx.ui

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringResourceQuotesTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    private val valuesDir: File = listOf(File("src/main/res/values"), File("app/src/main/res/values"))
        .firstOrNull(File::isDirectory) ?: error("Could not locate the values resource folder")

    @Test fun noUiStringContainsAStraightApostropheOrStraightDoubleQuote() {
        val offenders = uiTexts().filter { (_, text) -> '\'' in text || '"' in text }

        assertEquals(emptyMap(), offenders, "UI strings must use typographic quotes and apostrophes")
    }

    @Test fun theScanFindsTheUiStringsOfEveryValuesFile() {
        val texts = uiTexts()

        assertTrue(texts.size > 300, "expected the full set of strings and plural items, found ${texts.size}")
        assertTrue("onboarding_recovery_folder_title" in texts.keys)
    }

    private fun uiTexts(): Map<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val texts = linkedMapOf<String, String>()
        valuesDir.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy(File::getName).forEach { file ->
            val resources = builder.parse(file).documentElement
            for (i in 0 until resources.childNodes.length) {
                val element = resources.childNodes.item(i) as? Element ?: continue
                when (element.tagName) {
                    "string" -> texts[element.getAttribute("name")] = element.textContent

                    "plurals", "string-array" -> {
                        val items = element.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            texts["${element.getAttribute("name")}[$j]"] = items.item(j).textContent
                        }
                    }
                }
            }
        }
        return texts
    }
}
