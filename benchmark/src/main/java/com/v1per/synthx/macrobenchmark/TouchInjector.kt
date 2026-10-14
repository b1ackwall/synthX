package com.v1per.synthx.macrobenchmark

import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator

/**
 * One-finger straight-line gestures made of equal steps on an exact time grid.
 *
 * The player sheet measures release velocity from touch positions relative to itself, and it
 * moves under the finger, so that velocity comes out as roughly a tenth of the finger's speed
 * and is thrown off by any uneven step: one shorter final step reads as a reversal and sends the
 * sheet back where it came from. Equal steps keep the reading steady and in the drag direction.
 *
 * Two things would make the steps uneven, and the grid avoids both. Android resamples touch
 * samples that are less than 20 ms apart, and two samples that land in one display frame are
 * merged. [StepIntervalMs] is above the first limit and is two whole frames at 60 Hz, so every
 * step is delivered alone and unchanged. The cost: at 60 Hz the app sees a new finger position
 * on every second frame, not on every frame as with a real finger.
 *
 * UiDevice.swipe is not used because it waits for the app after each step, so its step length
 * (also about 33 ms at 60 Hz) and its event times depend on the phone's frame timing.
 */
internal object TouchInjector {
    const val StepIntervalMs = 33L

    /** Pause between the last move and the lift when there is no hold. */
    private const val LiftDelayMs = 8L

    /**
     * Touches down at [start], moves to [end] in [steps] equal steps, one per [StepIntervalMs],
     * then lifts: right away, or after resting at [end] for [holdMs]. No events are sent during
     * the hold, so the release velocity the app reads is that of the last steps either way.
     */
    fun swipe(start: Point, end: Point, steps: Int, holdMs: Long = 0) {
        require(steps > 0) { "steps must be positive, got $steps." }
        val downTime = SystemClock.uptimeMillis()
        var x = start.x.toFloat()
        var y = start.y.toFloat()
        send(MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        try {
            for (step in 1..steps) {
                // Positions and event times follow the grid exactly, whenever the thread wakes.
                val eventTime = downTime + step * StepIntervalMs
                SystemClock.sleep((eventTime - SystemClock.uptimeMillis()).coerceAtLeast(0))
                x = start.x + (end.x - start.x) * step / steps.toFloat()
                y = start.y + (end.y - start.y) * step / steps.toFloat()
                send(MotionEvent.ACTION_MOVE, downTime, eventTime, x, y)
            }
            SystemClock.sleep(if (holdMs > 0) holdMs else LiftDelayMs)
        } finally {
            // Always lift, so a failed move cannot leave a finger down on the app.
            send(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y)
        }
    }

    private fun send(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        // The same UiAutomation connection UiDevice uses; other flags would reconnect it.
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(Configurator.getInstance().uiAutomationFlags)
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(automation.injectInputEvent(event, false)) {
                "Could not inject touch ${MotionEvent.actionToString(action)} at ($x, $y)."
            }
        } finally {
            event.recycle()
        }
    }
}
