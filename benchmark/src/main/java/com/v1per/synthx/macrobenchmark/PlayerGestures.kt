package com.v1per.synthx.macrobenchmark

import android.graphics.Point
import androidx.test.uiautomator.UiDevice

/**
 * Vertical drags on the player sheet, in the equal steps [TouchInjector] explains. Every gesture
 * keeps one x, so it never crosses a horizontal touch slop, and none starts on the status bar or
 * the system gesture area.
 */
internal class PlayerGestures(private val device: UiDevice) {
    private companion object {
        /** 30 steps of 33 ms: one second of travel. */
        const val SlowDragSteps = 30

        /** The finger rests this long before it lifts. */
        const val HoldMs = 300L

        /** About 37% of the sheet's travel, past its 20% positional threshold. */
        const val SlowDragTravel = 0.35f

        /**
         * 6 steps of 33 ms: half the display in 200 ms. The sheet reads the release velocity
         * from the last 100 ms, so the swipe must outlast that with steady steps; a shorter
         * one (3 steps) is read as a reversal and the sheet goes back.
         */
        const val FlingSteps = 6
        const val FlingTravel = 0.51f

        /** Start height for a downward drag: on the album art, below the status bar. */
        const val CollapseStart = 0.22f
    }

    private val player = PlayerDriver(device)

    /** Drags the mini player up slowly, holds, then lifts. */
    fun slowDragExpand() {
        val start = miniCenter()
        slowDrag(start, travel(start, -SlowDragTravel))
        player.awaitState(expanded = true, action = "a slow upward drag with a held release")
    }

    /** Drags the expanded sheet down slowly, holds, then lifts. */
    fun slowDragCollapse() {
        val start = collapseStart()
        slowDrag(start, travel(start, SlowDragTravel))
        player.awaitState(expanded = false, action = "a slow downward drag with a held release")
    }

    fun flingExpand() {
        val start = miniCenter()
        fling(start, travel(start, -FlingTravel))
        player.awaitState(expanded = true, action = "an upward fling")
    }

    fun flingCollapse() {
        val start = collapseStart()
        fling(start, travel(start, FlingTravel))
        player.awaitState(expanded = false, action = "a downward fling")
    }

    private fun miniCenter(): Point =
        AppReadiness.findOrFail(device, Tags.MiniPlayer, "Mini player").visibleCenter

    private fun collapseStart(): Point =
        Point(device.displayWidth / 2, (device.displayHeight * CollapseStart).toInt())

    private fun travel(start: Point, displayFraction: Float): Point =
        Point(start.x, start.y + (device.displayHeight * displayFraction).toInt())

    private fun slowDrag(start: Point, end: Point) =
        TouchInjector.swipe(start, end, steps = SlowDragSteps, holdMs = HoldMs)

    private fun fling(start: Point, end: Point) =
        TouchInjector.swipe(start, end, steps = FlingSteps)
}
