package com.v1per.synthx.macrobenchmark

import android.graphics.Point
import androidx.test.uiautomator.UiDevice

/**
 * Vertical drags on the player sheet. Every gesture keeps one x, so it never crosses a
 * horizontal touch slop, and none starts on the status bar or the system gesture area.
 */
internal class PlayerGestures(private val device: UiDevice) {
    private companion object {
        /** UiAutomator injects one move per step and sleeps about 5 ms after each. */
        const val StepsPerSegment = 10

        /** 20 segments of 10 steps: about one second of travel. */
        const val SlowDragSegments = 20

        /** 6 stationary segments: about 300 ms, so the release velocity is zero. */
        const val HoldSegments = 6

        /** Past the sheet's 20% positional threshold, which a zero-velocity release must clear. */
        const val SlowDragTravel = 0.35f

        const val FlingTravel = 0.25f

        /** About 60 ms of travel, far above the 125 dp/s fling threshold. */
        const val FlingSteps = 12

        /** Start height for a downward drag: on the album art, below the status bar. */
        const val CollapseStart = 0.22f
    }

    private val player = PlayerDriver(device)

    /** Drags the mini player up slowly, holds, then releases. */
    fun slowDragExpand() {
        val start = miniCenter()
        slowDrag(start, travel(start, -SlowDragTravel))
        player.awaitState(expanded = true, action = "a slow upward drag with a held release")
    }

    /** Drags the expanded sheet down slowly, holds, then releases. */
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

    /** Evenly spaced points from [start] to [end], then [end] repeated for the hold. */
    private fun slowDrag(start: Point, end: Point) {
        val path = buildList {
            for (i in 0..SlowDragSegments) {
                add(Point(start.x, start.y + (end.y - start.y) * i / SlowDragSegments))
            }
            repeat(HoldSegments) { add(Point(end.x, end.y)) }
        }
        check(device.swipe(path.toTypedArray(), StepsPerSegment)) {
            "UiAutomator could not inject the slow drag from $start to $end."
        }
    }

    private fun fling(start: Point, end: Point) {
        check(device.swipe(start.x, start.y, end.x, end.y, FlingSteps)) {
            "UiAutomator could not inject the fling from $start to $end."
        }
    }
}
