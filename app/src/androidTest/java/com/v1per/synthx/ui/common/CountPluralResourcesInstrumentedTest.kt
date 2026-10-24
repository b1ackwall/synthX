package com.v1per.synthx.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.R
import com.v1per.synthx.data.scanner.LibraryScanWorker
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every count plural resolves from the real resources to plain singular and plural English. */
@RunWith(AndroidJUnit4::class)
class CountPluralResourcesInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER)
    val testTimeout = TestTimeouts.device()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = context.resources

    private fun plural(id: Int, count: Int, vararg extra: Any) = resources.getQuantityString(id, count, *extra)

    /** Checks 0, 1, 2 and 1000 against a template where [noun] is the only word that changes. */
    private fun assertNounFollowsCount(
        id: Int,
        singular: String,
        pluralNoun: String,
        template: (Int, String) -> String,
    ) {
        for (count in listOf(0, 1, 2, 1000)) {
            val noun = if (count == 1) singular else pluralNoun
            assertEquals("quantity $count", template(count, noun), plural(id, count, count))
        }
    }

    @Test fun destructiveActionsTitleFollowsTheCount() = assertNounFollowsCount(
        R.plurals.issues_destructive_actions_title_count,
        "change",
        "changes",
    ) { n, noun -> "$n $noun since your last backup" }

    @Test fun reconciledPendingCleanupsTitleFollowsTheCount() = assertNounFollowsCount(
        R.plurals.issues_reconciled_pending_cleanups_title_count,
        "change",
        "changes",
    ) { n, noun -> "$n interrupted $noun cleaned up on launch" }

    @Test fun missingArtTitleFollowsTheCount() = assertNounFollowsCount(
        R.plurals.issues_missing_art_title_count,
        "song",
        "songs",
    ) { n, noun -> "$n $noun missing cover art" }

    @Test fun destructiveActionWarningFollowsTheCount() = assertNounFollowsCount(
        R.plurals.destructive_action_warning_count,
        "action",
        "actions",
    ) { n, noun -> "$n destructive $noun since your last successful backup" }

    @Test fun restorePlaylistCountFollowsTheCount() = assertNounFollowsCount(
        R.plurals.restore_playlist_count,
        "playlist",
        "playlists",
    ) { n, noun -> "$n $noun" }

    @Test fun restorePlaylistEntryCountFollowsTheCount() = assertNounFollowsCount(
        R.plurals.restore_playlist_entry_count,
        "entry",
        "entries",
    ) { n, noun -> "$n playlist $noun" }

    @Test fun scanStatusLastKeepsTheTimeFirstAndFollowsTheCount() {
        for (count in listOf(0, 1, 2, 1000)) {
            val noun = if (count == 1) "issue" else "issues"
            assertEquals(
                "Last checked 5 min ago — $count $noun found",
                plural(R.plurals.issues_scan_status_last_count, count, "5 min ago", count),
            )
        }
    }

    @Test fun newArrivalsFoundFollowsTheCount() = assertNounFollowsCount(
        R.plurals.scan_status_new_arrivals_found_count,
        "song",
        "songs",
    ) { n, noun -> "$n new $noun found" }

    @Test fun verifyMissingFollowsTheCount() = assertNounFollowsCount(
        R.plurals.scan_status_verify_missing_count,
        "song",
        "songs",
    ) { n, noun -> "$n $noun newly missing" }

    @Test fun newArrivalsDegradedKeepsTheWarningAndFollowsTheCount() {
        for (count in listOf(0, 1, 2, 1000)) {
            val noun = if (count == 1) "song" else "songs"
            assertEquals(
                "New Arrivals scan incomplete — $count new $noun found. WARN",
                plural(R.plurals.scan_status_new_arrivals_degraded_count, count, count, "WARN"),
            )
        }
    }

    @Test fun verifyDegradedKeepsTheWarningAndFollowsTheCount() {
        for (count in listOf(0, 1, 2, 1000)) {
            val noun = if (count == 1) "song" else "songs"
            assertEquals(
                "Verification incomplete — $count $noun confirmed missing. WARN",
                plural(R.plurals.scan_status_verify_degraded_count, count, count, "WARN"),
            )
        }
    }

    @Test fun scanNotificationProgressNumbersTheNounByTheTotal() {
        assertEquals("1 of 1 song", LibraryScanWorker.progressText(context, current = 1, total = 1))
        assertEquals("1 of 5 songs", LibraryScanWorker.progressText(context, current = 1, total = 5))
        assertEquals("5 of 5 songs", LibraryScanWorker.progressText(context, current = 5, total = 5))
        assertEquals("3 of 1000 songs", LibraryScanWorker.progressText(context, current = 3, total = 1000))
    }
}
