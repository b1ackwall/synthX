package com.v1per.synthx.ui.common

import java.text.Normalizer
import java.util.Locale

private const val COMBINING_MARKS_START = 0x0300
private const val COMBINING_MARKS_END = 0x036F

/**
 * The comparison key for matching typed text against library text: a field matches when its key
 * contains the query's key. Both the query and the library text go through this function.
 *
 * Steps, in order: lowercase (root locale), decompose (NFD), drop a combining mark in
 * U+0300..U+036F only when the base character before it is a Latin letter, recompose (NFC), then
 * spell out the Latin letters that do not decompose: ø o, ł l, đ d, æ ae, œ oe, ß ss.
 *
 * Marks on every other script stay (Cyrillic й and ё, Greek, Hangul, Indic), and so do the kana
 * voicing marks U+3099 and U+309A, so が never matches か. The key can be longer or shorter than
 * the input, so offsets in the key do not map back to the original text.
 */
fun searchKey(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val withoutLatinMarks = StringBuilder(decomposed.length)
    var baseIsLatin = false
    decomposed.codePoints().forEach { codePoint ->
        val isMark = codePoint in COMBINING_MARKS_START..COMBINING_MARKS_END
        if (!isMark) baseIsLatin = isLatinLetter(codePoint)
        if (!(isMark && baseIsLatin)) withoutLatinMarks.appendCodePoint(codePoint)
    }
    val composed = Normalizer.normalize(withoutLatinMarks, Normalizer.Form.NFC)
    val key = StringBuilder(composed.length)
    for (char in composed) {
        when (char) {
            'ø' -> key.append('o')
            'ł' -> key.append('l')
            'đ' -> key.append('d')
            'æ' -> key.append("ae")
            'œ' -> key.append("oe")
            'ß' -> key.append("ss")
            else -> key.append(char)
        }
    }
    return key.toString()
}

private fun isLatinLetter(codePoint: Int): Boolean =
    Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN

/** An item together with the [searchKey] of each field a query is matched against. */
class SearchKeyed<T>(val item: T, private val keys: List<String>) {
    /** True when any field's key contains [queryKey], the [searchKey] of the trimmed query. */
    fun matches(queryKey: String): Boolean = keys.any { it.contains(queryKey) }
}

/** Computes each item's field keys once, so a list emission is keyed once however often it is searched. */
fun <T> List<T>.searchKeyed(vararg fields: (T) -> String): List<SearchKeyed<T>> =
    map { item -> SearchKeyed(item, fields.map { field -> searchKey(field(item)) }) }
