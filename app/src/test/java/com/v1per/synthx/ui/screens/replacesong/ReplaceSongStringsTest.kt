package com.v1per.synthx.ui.screens.replacesong

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
 * The Replace Song strings say what the code does, and "Nothing was changed" is only ever said
 * about a replace that did not commit.
 */
class ReplaceSongStringsTest {
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

    private val newAndChanged = mapOf(
        "replace_song_failed_replacement_unreadable" to
            "Couldn’t read the replacement song’s file. Nothing was changed.",
        "replace_song_done_housekeeping" to "Replacement complete, but some follow-up steps didn’t finish.",
        "replace_song_done_cleanup_pending" to
            "Replaced. The old file couldn’t be removed yet; synthX will try again next time it starts.",
        "replace_song_done_move_incomplete" to
            "Replaced, but moving the new file into your Music folder didn’t finish. " +
            "It’s still in Starred; you can move it from there.",
        "replace_song_stage_moving" to "Moving the new file into Music…",
        "replace_song_review_will_move" to "The replacement will be moved from New Arrivals into your Music folder.",
        "audio_cleanup_retry_file_in_library" to "That file is now part of your library, so it was left in place.",
    )

    @Test fun theNewStringsHaveTheirFinalText() {
        newAndChanged.forEach { (name, text) -> assertEquals(text, strings[name], name) }
    }

    @Test fun noStringSaysStarredInbox() {
        val offenders = strings.filterValues { "inbox" in it.lowercase() }.keys
        assertEquals(emptySet(), offenders, "the UI says Starred")
    }

    @Test fun theSummaryMatchesWhatTheReplaceDoes() {
        val summary = strings.getValue("replace_song_summary")

        assertTrue("date added" in summary, summary)
        assertTrue("Starred" in summary && "Recently Added" in summary, "both date-sorted lists: $summary")
        assertTrue("Copies of the old song in your queue are removed" in summary, summary)
        assertTrue("stops when nothing else is queued" in summary, summary)
        assertFalse("Music folder" in summary, "the move to Music is the review line's to mention: $summary")
    }

    @Test fun theNewAndChangedStringsTakeNoPlaceholders() {
        (newAndChanged.keys + "replace_song_summary").forEach { name ->
            assertFalse(Regex("%\\d*\\$?[sd]").containsMatchIn(strings.getValue(name)), name)
        }
    }

    @Test fun nothingWasChangedIsUsedOnlyForFailuresBeforeTheCommit() {
        val generic = Regex("""R\.string\.replace_song_failed_generic\b""")
        val users = ProductionSource.allCode().filterValues { generic.containsMatchIn(it) }.keys
        assertEquals(setOf("com/v1per/synthx/ui/screens/replacesong/ReplaceSongViewModel.kt"), users)

        // Each use sits in the catch of a call that either did not commit (replaceSong reports
        // everything after its commit inside Success) or never writes (previewReplaceSong).
        val viewModel = ProductionSource.code("com/v1per/synthx/ui/screens/replacesong/ReplaceSongViewModel.kt")
        val uses = generic.findAll(viewModel).map { it.range.first }.toList()
        assertEquals(2, uses.size)
        uses.forEach { at ->
            val before = viewModel.substring(0, at)
            val catchAt = before.lastIndexOf("catch (e: Throwable)")
            assertTrue(catchAt >= 0 && "Complete(" !in before.substring(catchAt), "generic message outside a catch")
        }
        val outcomes = ProductionSource.code("com/v1per/synthx/ui/screens/replacesong/ReplaceSongOutcomes.kt")
        assertFalse(generic.containsMatchIn(outcomes), "the committed-replace mapping never says nothing changed")
    }
}
