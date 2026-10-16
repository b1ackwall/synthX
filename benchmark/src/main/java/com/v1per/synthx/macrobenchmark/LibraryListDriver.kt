package com.v1per.synthx.macrobenchmark

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * Opens one of the Library tab's category screens and scrolls its list. Those screens have no
 * test tags, so the category row is found by its visible label inside the Library screen, and
 * the list is the largest scrollable node on the opened screen.
 *
 * Every swipe is the shared [ListSwipe] gesture on the left third of the list, above the mini
 * player. Rows on these screens only react to a tap or a long press, and a swipe is far longer
 * than a tap, so scrolling cannot play or edit anything. Pull-to-refresh exists only on the
 * Library screen, and [requireListShowing] fails before any swipe could land there.
 */
internal class LibraryListDriver(private val device: UiDevice) {
    /** A Library category; [label] mirrors the app's screen_* string. */
    enum class Category(val label: String) {
        Songs("Songs"),
        Starred("Starred"),
        Playlists("Playlists"),
    }

    private companion object {
        const val ListTimeoutMs = 10_000L
        const val SignatureRows = 3
        const val RestTimeoutMs = 5_000L
        const val RestPollMs = 150L

        const val LongListHint = "These numbers need a list several screens long in synthX bench."
    }

    /** From the Library screen, taps [category]'s row and waits for its list. */
    fun open(category: Category) {
        val library = AppReadiness.findOrFail(device, Tags.LibraryScreen, "Library screen")
        val row = findCategoryRow(library, category)
        checkNotNull(row) {
            "No '${category.label}' row on the Library screen. Visible text: ${visibleText()}."
        }
        // The row's texts, including a count badge when there is one.
        val rowNode = row.parent?.takeUnless { it.isScrollable } ?: row
        report("synthx.${category.name}.libraryRowText", allText(rowNode))
        row.click()
        check(device.wait(Until.gone(By.res(Tags.LibraryScreen)), ListTimeoutMs)) {
            "Tapping '${category.label}' did not leave the Library screen within " +
                "${ListTimeoutMs / 1000} s."
        }
        requireList(category)
    }

    /** Fails if the Library screen is showing, so no swipe can reach its pull-to-refresh. */
    fun requireListShowing(category: Category) {
        check(!device.hasObject(By.res(Tags.LibraryScreen))) {
            "The Library screen is showing instead of ${category.label}; stopping before a " +
                "swipe could pull to refresh and start a library scan."
        }
        requireList(category)
    }

    /** Fails unless the list fills the space above the mini player. */
    fun requireScrollLane(category: Category): ScrollLane {
        val list = requireList(category).visibleBounds
        val chromeTop = AppReadiness.findOrFail(device, Tags.MiniPlayer, "Mini player")
            .visibleBounds.top
        val bottom = minOf(list.bottom, chromeTop)
        val height = bottom - list.top
        check(height > device.displayHeight / 10) {
            "The ${category.label} list shows only $height px above the mini player; too short " +
                "to scroll safely."
        }
        report("synthx.${category.name}.visibleRows", topRows(Int.MAX_VALUE).size.toString())
        return ScrollLane(
            x = list.left + list.width() * 3 / 10,
            topY = list.top + height * 15 / 100,
            bottomY = bottom - height * 15 / 100,
        )
    }

    /**
     * One unmeasured pass of the measured gesture. It proves the list is long enough for every
     * swipe toward the end to move it, and that the same number back returns it to its start.
     */
    fun rehearseScroll(category: Category, lane: ScrollLane, swipesEachWay: Int) {
        val atStart = topRowsSignature(category)
        var previous = atStart
        repeat(swipesEachWay) { index ->
            ListSwipe.towardEnd(lane)
            val now = topRowsSignature(category)
            check(now != previous) {
                "Swipe ${index + 1} toward the end of the ${category.label} list did not move it " +
                    "(first rows still $now), so the list is too short. $LongListHint"
            }
            previous = now
        }
        repeat(swipesEachWay) { ListSwipe.towardStart(lane) }
        val back = topRowsSignature(category)
        check(back == atStart) {
            "After $swipesEachWay swipes each way the ${category.label} list did not return to " +
                "its start. First rows before: $atStart. After: $back."
        }
    }

    private fun findCategoryRow(library: UiObject2, category: Category): UiObject2? {
        // A row with a badge merges its texts, for example "Starred, 12".
        val exact = Pattern.compile("^${Pattern.quote(category.label)}(, .*)?$")
        return library.findObject(By.text(exact))
            ?: library.findObject(By.textStartsWith(category.label))
    }

    private fun requireList(category: Category): UiObject2 {
        val lists = device.wait(Until.findObjects(scrollable()), ListTimeoutMs)
        val list = lists?.maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
        return checkNotNull(list) {
            "No scrollable list on the ${category.label} screen within ${ListTimeoutMs / 1000} " +
                "s; the list may be empty. Visible text: ${visibleText()}."
        }
    }

    /**
     * Text of the first rows, read once the list is at rest: two equal reads in a row. While a
     * fling is still recycling rows a read can fail or catch the list mid-scroll.
     */
    private fun topRowsSignature(category: Category): List<String> {
        val deadline = SystemClock.uptimeMillis() + RestTimeoutMs
        var previous: List<String>? = null
        while (true) {
            val now = try {
                requireList(category)
                topRows(SignatureRows)
            } catch (_: StaleObjectException) {
                null
            }
            if (now != null && now == previous) return now
            check(SystemClock.uptimeMillis() < deadline) {
                "The ${category.label} list did not come to rest within " +
                    "${RestTimeoutMs / 1000} s. Last read: ${now ?: "rows changed mid-read"}."
            }
            previous = now
            SystemClock.sleep(RestPollMs)
        }
    }

    private fun topRows(count: Int): List<String> {
        val list = device.findObjects(scrollable())
            .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() } ?: return emptyList()
        return list.children
            .map { it.visibleBounds.top to allText(it) }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first }
            .take(count)
            .map { it.second }
    }

    private fun scrollable(): BySelector = By.scrollable(true)

    private fun visibleText(): List<String> =
        device.findObjects(By.text(Pattern.compile(".+"))).mapNotNull { it.text }.take(20)

    private fun allText(node: UiObject2): String =
        (listOfNotNull(node.text) + node.children.map(::allText)).joinToString("|")

    /** Adds one key to the instrumentation output, next to the benchmark's own results. */
    private fun report(key: String, value: String) {
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(StatusInProgress, Bundle().apply { putString(key, value) })
    }
}

/** Instrumentation status code for an in-progress report, as Macrobenchmark uses. */
private const val StatusInProgress = 2
