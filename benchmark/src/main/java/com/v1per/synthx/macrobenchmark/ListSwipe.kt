package com.v1per.synthx.macrobenchmark

import android.graphics.Point
import android.os.SystemClock

/** One fixed lane for every swipe of a scenario. */
internal class ScrollLane(val x: Int, val topY: Int, val bottomY: Int)

/** The list scroll gesture every scroll scenario shares, so their numbers are comparable. */
internal object ListSwipe {
    /** Covers a list fling coming to rest. */
    private const val PauseMs = 900L

    /** 10 steps of 33 ms: a brisk scroll that ends in a fling. */
    private const val Steps = 10

    /** One straight vertical swipe from [fromY] to [toY], then a pause for the fling to settle. */
    fun vertical(x: Int, fromY: Int, toY: Int) {
        TouchInjector.swipe(Point(x, fromY), Point(x, toY), steps = Steps)
        SystemClock.sleep(PauseMs)
    }

    /** Finger moves up: the list scrolls toward its end. */
    fun towardEnd(lane: ScrollLane) = vertical(lane.x, lane.bottomY, lane.topY)

    /** Finger moves down: the list scrolls back toward its start. */
    fun towardStart(lane: ScrollLane) = vertical(lane.x, lane.topY, lane.bottomY)
}
