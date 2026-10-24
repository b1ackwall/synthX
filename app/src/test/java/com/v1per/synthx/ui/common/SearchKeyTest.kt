package com.v1per.synthx.ui.common

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchKeyTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.jvm()

    @Test
    fun latinAccentsAreDroppedAndCaseIsFolded() {
        assertEquals("tiesto", searchKey("Tiësto"))
        assertEquals("tiesto", searchKey("TIËSTO"))
        assertEquals("beyonce", searchKey("Beyoncé"))
        assertEquals("motley crue", searchKey("Mötley Crüe"))
        assertEquals("sigur ros", searchKey("Sigur Rós"))
    }

    @Test
    fun aDecomposedAccentGivesTheSameKeyAsTheComposedLetter() {
        assertEquals(searchKey("ë"), searchKey("ë"))
        assertEquals("e", searchKey("ë"))
        assertEquals("e", searchKey("ë́"))
    }

    @Test
    fun lettersThatDoNotDecomposeAreSpelledOut() {
        assertEquals("bjork", searchKey("Bjørk"))
        assertEquals("lodz", searchKey("Łódź"))
        assertEquals("dorde", searchKey("Đorđe"))
        assertEquals("aeon", searchKey("Æon"))
        assertEquals("coeur", searchKey("Cœur"))
        assertEquals("strasse", searchKey("Straße"))
        assertEquals("strasse", searchKey("STRAẞE"))
    }

    @Test
    fun marksOnOtherScriptsAreKept() {
        assertEquals("привет", searchKey("Привет"))
        assertEquals("йод", searchKey("Йод"))
        assertEquals("ёлка", searchKey("Ёлка"))
        assertEquals("\u0439", searchKey("\u0438\u0306"))
        assertEquals("ά", searchKey("Ά"))
    }

    @Test
    fun kanaHangulAndDevanagariKeepTheirVoicingMarksAndSyllables() {
        assertEquals("が", searchKey("が"))
        assertEquals("か", searchKey("か"))
        assertEquals("ガ", searchKey("ガ"))
        assertEquals("カ", searchKey("カ"))
        assertEquals("\u304C", searchKey("\u304B\u3099"))
        assertEquals("한글", searchKey("한글"))
        assertEquals("한글", searchKey("한글"))
        assertEquals("हिन्दी", searchKey("हिन्दी"))
    }

    @Test
    fun aKeyContainsTheKeyOfAnyPartOfItsText() {
        assertTrue(searchKey("Tiësto - Red Lights").contains(searchKey("tiesto")))
        assertTrue(searchKey("Tiesto").contains(searchKey("tiësto")))
        assertFalse(searchKey("か").contains(searchKey("が")))
        assertFalse(searchKey("カ").contains(searchKey("か")))
        assertFalse(searchKey("한글").contains(searchKey("하")))
    }

    @Test
    fun oddInputDoesNotThrow() {
        assertEquals("", searchKey(""))
        assertEquals("̈́", searchKey("̈́"))
        assertEquals("a\ud800b", searchKey("A\ud800B"))
        assertEquals("a", searchKey("ä́̂"))
        assertEquals("i", searchKey("İ"))
        val long = "Tiësto ".repeat(50_000)
        assertEquals("tiesto ".repeat(50_000), searchKey(long))
    }

    @Test
    fun searchKeyedMatchesOnAnyFieldAndKeepsTheItem() {
        val keyed = listOf("Tiësto" to "Red Lights", "Daft Punk" to "Around").searchKeyed({ it.first }, { it.second })
        assertEquals(listOf("Tiësto" to "Red Lights"), keyed.filter { it.matches(searchKey("tiesto")) }.map { it.item })
        assertEquals(listOf("Daft Punk" to "Around"), keyed.filter { it.matches(searchKey("ROUND")) }.map { it.item })
    }
}
