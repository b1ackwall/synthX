package com.v1per.synthx.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.abs

/**
 * P8-T5 collapsed ~100 individual `drawLine` calls for the unlit backbone into one stroked
 * `drawPath`. This proves that changed nothing visible: [drawPlaylistArt] is rendered next to a
 * reference that still draws the backbone one line per segment — exactly what the code did before —
 * from the very same model, and the two bitmaps are compared pixel by pixel.
 *
 * The two are visually identical but NOT bit-identical, and this test is deliberately not written
 * as if they were. Skia rasterises a stroked path with several subpaths marginally differently from
 * the same subpaths drawn one call each: measured on an x86_64 emulator over 150 covers at
 * 56-140 px, ~4% of pixels differ, by ~1/255 on average and 5/255 at worst, and every one of them
 * lies within a pixel of a stroke of the 10%-alpha backbone, none farther away. One subpath per
 * `drawPath` reproduces the old output exactly, so the difference is real but comes from putting
 * several in one path — which is the whole point of the change. The bounds below are set to absorb
 * that noise and still fail on anything a person could see: a wrong alpha, width, cap, or a
 * missing segment (see the mutation results recorded in the P8-T5 report).
 *
 * The reference restates `UnlitSegmentColor` (white at alpha 0.1) and `DiamondAlpha` (0.5) rather
 * than importing them, so a change to either constant in production shows up here as a mismatch
 * to be looked at deliberately, not silently followed.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistArtBackboneEquivalenceInstrumentedTest {

    @Test fun singlePathBackboneRendersTheSameAsOneLinePerSegment() {
        val rng = Random(20260919L)
        var worstChannelDiff = 0
        var differingPixels = 0
        var totalPixels = 0
        var sumChannelDiff = 0L
        var differingOutsideBackbone = 0
        // 80..140 px is 40dp at 2x..3.5x, the range of real phones; 56 px is where the 1px stroke
        // floor is engaged, which is the case most likely to disagree if any does.
        for (sizePx in listOf(56, 80, 105, 110, 120, 140)) {
            repeat(SEEDS_PER_SIZE) { i ->
                val seed = rng.nextLong()
                val hue = (i * 4) % 360
                val model = playlistArtRenderModel(hue, seed, sizePx.toFloat())

                val unlit = unlitSegments(seed, sizePx)
                val actual = render(sizePx) { drawPlaylistArt(model) }
                val expected = render(sizePx) { drawReference(model, unlit) }
                // A pixel farther than half a stroke plus one pixel of anti-aliasing from every
                // unlit segment is not the backbone's to paint, so the renders must agree there.
                val reach = model.strokeWidth / 2f + 1f

                for (p in actual.indices) {
                    val diff = maxChannelDiff(actual[p], expected[p])
                    worstChannelDiff = maxOf(worstChannelDiff, diff)
                    sumChannelDiff += diff
                    if (diff > 0) {
                        differingPixels++
                        if (distanceToNearest(unlit, (p % sizePx) + 0.5f, (p / sizePx) + 0.5f) > reach) differingOutsideBackbone++
                    }
                }
                totalPixels += actual.size
            }
        }
        val meanDiff = sumChannelDiff.toDouble() / totalPixels
        android.util.Log.i("PlaylistArtBench", "backbone equivalence: worst channel diff=$worstChannelDiff/255, " +
            "mean diff=%.3f/255, differing pixels=%.2f%% of $totalPixels, differing outside backbone=$differingOutsideBackbone"
                .format(meanDiff, 100.0 * differingPixels / totalPixels))
        assertEquals("Pixels differing farther than a stroke edge from any backbone segment", 0, differingOutsideBackbone)
        assertTrue("Worst pixel differs by $worstChannelDiff/255 (bound $MAX_CHANNEL_DIFF)", worstChannelDiff <= MAX_CHANNEL_DIFF)
        assertTrue("Mean pixel difference %.3f/255 (bound $MAX_MEAN_DIFF)".format(meanDiff), meanDiff <= MAX_MEAN_DIFF)
    }

    @Test fun theBackboneIsActuallyVisibleSoTheComparisonCannotPassVacuously() {
        // If both renders were blank (or the backbone drew nothing) the test above would pass for
        // the wrong reason. A cover with every unlit segment removed must differ from one with them.
        val sizePx = 120
        val seed = 12345L
        val model = playlistArtRenderModel(30, seed, sizePx.toFloat())
        val withBackbone = render(sizePx) { drawPlaylistArt(model) }
        val withoutBackbone = render(sizePx) { drawReference(model, emptyList()) }

        val differing = withBackbone.indices.count { withBackbone[it] != withoutBackbone[it] }
        assertTrue("Backbone painted only $differing pixels; the equivalence test would be vacuous", differing > 200)
    }

    private fun distanceToNearest(segments: List<Pair<Offset, Offset>>, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for ((a, b) in segments) {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val t = (((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
            best = minOf(best, kotlin.math.hypot(x - (a.x + t * dx), y - (a.y + t * dy)))
        }
        return best
    }

    private fun render(sizePx: Int, draw: DrawScope.() -> Unit): IntArray {
        val image = ImageBitmap(sizePx, sizePx)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(sizePx.toFloat(), sizePx.toFloat()), draw)
        return IntArray(sizePx * sizePx).also { image.readPixels(it, width = sizePx, height = sizePx) }
    }

    private fun maxChannelDiff(a: Int, b: Int): Int =
        maxOf(
            abs((a ushr 24) - (b ushr 24)),
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF)),
        )

    private companion object {
        const val SEEDS_PER_SIZE = 25

        // Observed 5 and ~0.04. Roughly 1.6x and 2.5x headroom: enough for another GPU/Skia build's
        // rounding, not enough to hide a stroke that is 10% too wide or an alpha of 0.12 vs 0.1.
        const val MAX_CHANNEL_DIFF = 8
        const val MAX_MEAN_DIFF = 0.10
    }
}
