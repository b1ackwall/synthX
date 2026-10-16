package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/**
 * Drives queue mode on the expanded player. Queue mode is only observable while the sheet is
 * expanded and at least one song is queued, because the list is the only tagged queue element.
 *
 * Scrolling is one straight vertical swipe on the left third of the list. A row's remove swipe
 * needs horizontal travel and its reorder handle sits at the right edge, so neither can start
 * from this lane; a swipe is also far longer than a tap, so it cannot play a row.
 */
internal class QueueDriver(private val device: UiDevice) {
    private companion object {
        /** Covers the 300 ms queue crossfade; no UI queries run while it animates. */
        const val TogglePauseMs = 700L
        const val ToggleTimeoutMs = 3_000L
        const val PollMs = 100L

        const val SignatureRows = 3
        const val RestTimeoutMs = 5_000L
        const val RestPollMs = 150L

        const val LongQueueHint = "In synthX bench, play the first song of a long list " +
            "(Library > Songs) so many songs follow it, then pause."
    }

    /** Meaningful only while the sheet is expanded. */
    fun isQueueMode(): Boolean = device.hasObject(By.res(Tags.QueueList))

    /** Taps the queue button, waits out the crossfade, then confirms the mode flipped. */
    fun toggle() {
        val target = !isQueueMode()
        AppReadiness.findOrFail(device, Tags.QueueButton, "Queue button").click()
        SystemClock.sleep(TogglePauseMs)
        val deadline = SystemClock.uptimeMillis() + ToggleTimeoutMs
        while (isQueueMode() != target) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Queue mode did not turn ${if (target) "on" else "off"} within " +
                    "${ToggleTimeoutMs / 1000} s after tapping the queue button. With no upcoming " +
                    "songs the queue list never shows; queue a longer list in synthX bench."
            }
            SystemClock.sleep(PollMs)
        }
    }

    fun ensureQueueMode(on: Boolean) {
        if (isQueueMode() == on) return
        showControls()
        toggle()
    }

    /** A net downward drag on the list brings back controls that an upward scroll hid. */
    fun showControls() {
        if (device.hasObject(By.res(Tags.QueueButton))) return
        val list = AppReadiness.findOrFail(device, Tags.QueueList, "Queue list").visibleBounds
        val x = list.left + list.width() * 3 / 10
        ListSwipe.vertical(x, list.top + list.height() / 4, list.top + list.height() / 2)
        AppReadiness.findOrFail(device, Tags.QueueButton, "Queue button")
    }

    /**
     * Fails unless the upcoming songs fill the list; call in queue mode with controls showing.
     * The lane is measured while the controls are showing.
     */
    fun requireScrollLane(): ScrollLane {
        val list = AppReadiness.findOrFail(device, Tags.QueueList, "Queue list").visibleBounds
        val controlsTop = AppReadiness.findOrFail(device, Tags.SeekBar, "Seek bar").visibleBounds.top
        val height = controlsTop - list.top
        check(height > device.displayHeight / 10) {
            "The queue list shows only $height px above the controls; too short to scroll safely."
        }
        val rows = device.findObjects(By.res(Tags.QueueRow))
        val rowsEnd = rows.maxOfOrNull { it.visibleBounds.bottom } ?: list.top
        check(rowsEnd >= list.bottom) {
            "The ${rows.size} upcoming songs end at y=$rowsEnd and the list at y=${list.bottom}, " +
                "so there is nothing to scroll. $LongQueueHint"
        }
        return ScrollLane(
            x = list.left + list.width() * 3 / 10,
            topY = list.top + height * 15 / 100,
            bottomY = controlsTop - height * 15 / 100,
        )
    }

    /**
     * One unmeasured pass of the measured gesture. It proves the queue is long enough for both
     * swipes toward the end to move the list, and that two swipes back return it to its start.
     */
    fun rehearseScroll(lane: ScrollLane, swipesEachWay: Int) {
        val atStart = topRowsSignature()
        var previous = atStart
        repeat(swipesEachWay) { index ->
            scrollTowardEnd(lane)
            val now = topRowsSignature()
            check(now != previous) {
                "Swipe ${index + 1} toward the end of the queue did not move the list (first " +
                    "rows still $now), so the queue is too short for this scenario. $LongQueueHint"
            }
            previous = now
        }
        repeat(swipesEachWay) { scrollTowardStart(lane) }
        showControls()
        val back = topRowsSignature()
        check(back == atStart) {
            "After $swipesEachWay swipes each way the queue did not return to its start. " +
                "First rows before: $atStart. After: $back."
        }
    }

    /** Finger moves up: the list scrolls toward its end and the controls fade out. */
    fun scrollTowardEnd(lane: ScrollLane) = ListSwipe.towardEnd(lane)

    /** Finger moves down: the list scrolls back toward its start and the controls return. */
    fun scrollTowardStart(lane: ScrollLane) = ListSwipe.towardStart(lane)

    /**
     * Text of the first rows, to detect an accidental reorder or removal. Read once the list is
     * at rest, which is two equal reads in a row: while a fling is still recycling rows a read
     * can fail or catch the list mid-scroll.
     */
    fun topRowsSignature(): List<String> {
        val deadline = SystemClock.uptimeMillis() + RestTimeoutMs
        var previous: List<String>? = null
        while (true) {
            val now = readTopRows()
            if (now != null && now == previous) return now
            check(SystemClock.uptimeMillis() < deadline) {
                "The queue list did not come to rest within ${RestTimeoutMs / 1000} s, so its " +
                    "first rows could not be read. Last read: ${now ?: "rows changed mid-read"}."
            }
            previous = now
            SystemClock.sleep(RestPollMs)
        }
    }

    /** Null when a row left the tree between being found and being read. */
    private fun readTopRows(): List<String>? =
        try {
            device.findObjects(By.res(Tags.QueueRow))
                .map { it.visibleBounds.top to allText(it) }
                .sortedBy { it.first }
                .take(SignatureRows)
                .map { it.second }
        } catch (_: StaleObjectException) {
            null
        }

    private fun allText(node: UiObject2): String =
        (listOfNotNull(node.text) + node.children.map(::allText)).joinToString("|")
}
