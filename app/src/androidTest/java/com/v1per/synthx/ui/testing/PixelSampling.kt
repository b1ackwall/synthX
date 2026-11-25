package com.v1per.synthx.ui.testing

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs

/** Per-channel tolerance for pixel checks: three steps of 255. */
const val PIXEL_COLOR_TOLERANCE = 3f / 255f

private const val MIN_ATTEMPTS = 5
private const val MIN_WINDOW_MS = 5_000L
private const val RETRY_PAUSE_MS = 100L

/** True when [actual] matches [expected] in red, green and blue within [tolerance]. */
fun colorsMatch(expected: Color, actual: Color, tolerance: Float = PIXEL_COLOR_TOLERANCE): Boolean =
    abs(expected.red - actual.red) <= tolerance &&
        abs(expected.green - actual.green) <= tolerance &&
        abs(expected.blue - actual.blue) <= tolerance

/** This color drawn at [alpha] over [bottom], channel by channel. */
fun Color.over(bottom: Color, alpha: Float): Color = Color(
    red = red * alpha + bottom.red * (1f - alpha),
    green = green * alpha + bottom.green * (1f - alpha),
    blue = blue * alpha + bottom.blue * (1f - alpha),
)

/**
 * Captures with [capture] until [check] accepts the image, then returns. [check] returns null when the image is
 * as expected, or a description of what it read instead.
 *
 * A capture can return the frame drawn before the last layout change, so a single capture may show a control
 * where it used to be. This keeps capturing for at least [MIN_ATTEMPTS] attempts and at least [MIN_WINDOW_MS],
 * whichever takes longer, and then fails with the last description [check] gave, so a real regression still
 * fails.
 */
fun ComposeTestRule.assertImageEventually(
    description: String,
    capture: () -> ImageBitmap,
    check: (ImageBitmap) -> String?,
) {
    val start = SystemClock.uptimeMillis()
    var attempts = 0
    var lastRead = "nothing read"
    var lastError: Throwable? = null
    while (attempts < MIN_ATTEMPTS || SystemClock.uptimeMillis() - start < MIN_WINDOW_MS) {
        attempts++
        try {
            val mismatch = check(capture()) ?: return
            lastRead = mismatch
            lastError = null
        } catch (e: AssertionError) {
            lastError = e
        } catch (e: RuntimeException) {
            lastError = e
        }
        waitForIdle()
        SystemClock.sleep(RETRY_PAUSE_MS)
    }
    throw AssertionError("$description: $lastRead after $attempts captures", lastError)
}

/**
 * Asserts that the pixel at [position] (from the captured image's size) of [capture] matches [expected],
 * re-capturing as [assertImageEventually] does.
 */
fun ComposeTestRule.assertPixelEventually(
    description: String,
    expected: Color,
    capture: () -> ImageBitmap,
    tolerance: Float = PIXEL_COLOR_TOLERANCE,
    position: (ImageBitmap) -> IntOffset,
) = assertImageEventually(description, capture) { image ->
    val at = position(image)
    val actual = image.toPixelMap()[at.x, at.y]
    if (colorsMatch(expected, actual, tolerance)) null else "expected $expected at $at, read $actual"
}
