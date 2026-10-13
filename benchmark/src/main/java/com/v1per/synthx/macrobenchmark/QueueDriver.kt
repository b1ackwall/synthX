package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import androidx.test.uiautomator.By
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

        /** Covers a list fling coming to rest. */
        const val ScrollPauseMs = 900L

        /** About 100 ms of travel: a brisk scroll that ends in a fling. */
        const val ScrollSteps = 20

        /** Rows that must be on screen for the list to have something to scroll. */
        const val MinScrollRows = 8
        const val SignatureRows = 3
    }

    /** One fixed lane for every swipe of a scenario, measured while the controls are showing. */
    class ScrollLane(val x: Int, val topY: Int, val bottomY: Int)

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
        swipe(x, list.top + list.height() / 4, list.top + list.height() / 2)
        AppReadiness.findOrFail(device, Tags.QueueButton, "Queue button")
    }

    /** Fails unless the queue is long enough to scroll; call in queue mode with controls showing. */
    fun requireScrollLane(): ScrollLane {
        val rows = device.findObjects(By.res(Tags.QueueRow)).size
        check(rows >= MinScrollRows) {
            "Only $rows upcoming songs are on screen; the queue scroll needs at least " +
                "$MinScrollRows. In synthX bench, play the first song of a long list (Library > " +
                "Songs) so many songs follow it, then pause."
        }
        val list = AppReadiness.findOrFail(device, Tags.QueueList, "Queue list").visibleBounds
        val controlsTop = AppReadiness.findOrFail(device, Tags.SeekBar, "Seek bar").visibleBounds.top
        val height = controlsTop - list.top
        check(height > device.displayHeight / 10) {
            "The queue list shows only $height px above the controls; too short to scroll safely."
        }
        return ScrollLane(
            x = list.left + list.width() * 3 / 10,
            topY = list.top + height * 15 / 100,
            bottomY = controlsTop - height * 15 / 100,
        )
    }

    /** Finger moves up: the list scrolls toward its end and the controls fade out. */
    fun scrollTowardEnd(lane: ScrollLane) = swipe(lane.x, lane.bottomY, lane.topY)

    /** Finger moves down: the list scrolls back toward its start and the controls return. */
    fun scrollTowardStart(lane: ScrollLane) = swipe(lane.x, lane.topY, lane.bottomY)

    /** Text of the first rows, to detect an accidental reorder or removal. */
    fun topRowsSignature(): List<String> =
        device.findObjects(By.res(Tags.QueueRow))
            .sortedBy { it.visibleBounds.top }
            .take(SignatureRows)
            .map(::allText)

    private fun swipe(x: Int, fromY: Int, toY: Int) {
        check(device.swipe(x, fromY, x, toY, ScrollSteps)) {
            "UiAutomator could not inject the queue scroll at x=$x from y=$fromY to y=$toY."
        }
        SystemClock.sleep(ScrollPauseMs)
    }

    private fun allText(node: UiObject2): String =
        (listOfNotNull(node.text) + node.children.map(::allText)).joinToString("|")
}
