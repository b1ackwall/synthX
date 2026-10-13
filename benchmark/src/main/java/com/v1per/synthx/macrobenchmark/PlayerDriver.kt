package com.v1per.synthx.macrobenchmark

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

/** Drives the player sheet through its test tags. Only taps and system back; no data changes. */
internal class PlayerDriver(private val device: UiDevice) {
    private companion object {
        /** Covers the sheet's spring; no UI queries run while it animates. */
        const val TransitionPauseMs = 1_000L
        const val TransitionTimeoutMs = 3_000L
        const val PollMs = 100L
    }

    /** Expanded once the sheet covers more than half the display. */
    fun isExpanded(): Boolean {
        val sheet = device.findObject(By.res(Tags.PlayerSheet)) ?: return false
        return sheet.visibleBounds.height() > device.displayHeight / 2
    }

    fun expand() {
        AppReadiness.findOrFail(device, Tags.MiniPlayer, "Mini player").click()
        awaitState(expanded = true, action = "tap on the mini player")
    }

    fun collapse() {
        device.pressBack()
        awaitState(expanded = false, action = "system back")
    }

    /** Back is pressed only while expanded, so it can never leave the app. */
    fun ensureCollapsed() {
        if (isExpanded()) collapse()
    }

    fun ensureExpanded() {
        if (!isExpanded()) expand()
    }

    /** The song title from the mini row, which stays in the tree while expanded; null if unreadable. */
    fun currentTitle(): String? {
        val mini = device.findObject(By.res(Tags.MiniPlayer)) ?: return null
        return firstText(mini)
    }

    /** Waits out the fixed pause, then polls until the sheet reaches [expanded]. */
    fun awaitState(expanded: Boolean, action: String) {
        SystemClock.sleep(TransitionPauseMs)
        val deadline = SystemClock.uptimeMillis() + TransitionTimeoutMs
        while (isExpanded() != expanded) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Player sheet did not ${if (expanded) "expand" else "collapse"} within " +
                    "${TransitionTimeoutMs / 1000} s after $action."
            }
            SystemClock.sleep(PollMs)
        }
    }

    private fun firstText(node: UiObject2): String? {
        node.text?.takeIf { it.isNotBlank() }?.let { return it }
        return node.children.firstNotNullOfOrNull(::firstText)
    }
}
