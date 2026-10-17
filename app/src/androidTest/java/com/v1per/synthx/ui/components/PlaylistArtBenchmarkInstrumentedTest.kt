package com.v1per.synthx.ui.components

import android.graphics.RenderNode
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * Informational timings for [PlaylistArt]'s per-row cost (P8-T5) — NOT a pass/fail gate. Wall-clock
 * numbers on a shared emulator or a thermally throttled phone are far too noisy to assert on, so
 * the timings are only logged (tag [TAG], read with `adb logcat -d -s PlaylistArtBench`). Four tests
 * also check that what they timed is correct (lit segments kept, cache hits and eviction, pixels
 * drawn); none asserts a time. Medians are used throughout because a single GC pause or scheduler
 * hiccup would otherwise dominate a mean.
 *
 * What each line measures, per cover, at the real 40dp size for this device's density:
 *  - `walk`: [generatePlaylistArt] alone — the seeded walk.
 *  - `build`: [playlistArtRenderModel] — walk plus scaling, brushes and paths. `build - walk` is
 *    the part a pattern-only cache could not remove.
 *  - `record`: replaying [drawPlaylistArt] into a [RenderNode] display list, which is the work
 *    Compose does at item entry for a row that is not already recorded.
 *  - `raster`: the same draw pass onto a software bitmap, a CPU-only proxy for how much drawing
 *    the model asks for (it is not GPU time).
 *  - `cache hit`: [cachedPlaylistArtRenderModel] for a key already held. `record, cold` includes the
 *    one-time creation of each lit brush's gradient shader, which a cached model keeps; `record,
 *    warm` is what a scrolled-back row pays now. The `new`/`old` pairs draw the same model with the
 *    single-path and per-segment backbone in the same run, so they compare like with like.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistArtBenchmarkInstrumentedTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.device()

    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    private val sizePx = 40f * density

    // Random 64-bit seeds and spread hues, like real stored rows — not a run of neighbouring seeds.
    private val seeds = Random(20260919L).let { rng -> LongArray(SAMPLES + WARMUP) { rng.nextLong() } }
    private val hues = IntArray(SAMPLES + WARMUP) { (it * 4) % 360 }

    @Test fun missCostSplitIntoWalkAndModelBuild() {
        val walk = timeEach { generatePlaylistArt(seeds[it]) }
        val build = timeEach { playlistArtRenderModel(hues[it], seeds[it], sizePx) }
        report("walk", walk)
        report("build (walk + brushes/paths/offsets)", build)
        Log.i(TAG, "build minus walk (what a pattern-only cache cannot remove): median %.1f us".format(median(build) - median(walk)))
        val lit = seeds.take(SAMPLES).map { generatePlaylistArt(it).litSegments.size }.sorted()
        Log.i(TAG, "sample lit segments: median=${lit[lit.size / 2]} mean=%.1f (each lit segment costs one Brush + one draw op)".format(lit.average()))
        // What was timed is right: the model keeps exactly the walk's lit segments.
        for (i in 0 until SAMPLES) {
            val expected = generatePlaylistArt(seeds[i]).litSegments.size
            assertTrue("seed ${seeds[i]} lights nothing", expected > 0)
            assertEquals("seed ${seeds[i]}", expected, playlistArtRenderModel(hues[i], seeds[i], sizePx).lit.size)
        }
    }

    @Test fun drawOpCountBeforeAndAfterBackboneCollapse() {
        // Analytic, but computed from real models so it reflects the actual lit/diamond mix.
        // Before: background + one line per unlit segment (144 - lit) + diamonds + one per lit.
        // After: background + one backbone path + diamonds + one per lit.
        var before = 0L
        var after = 0L
        for (i in 0 until SAMPLES) {
            val model = playlistArtRenderModel(hues[i], seeds[i], sizePx)
            val lit = model.lit.size
            val diamonds = model.diamonds.size
            before += 1 + (PlaylistArtGeometry.segments.size - lit) + diamonds + lit
            after += 1 + 1 + diamonds + lit
        }
        Log.i(TAG, "draw ops per cover (mean of $SAMPLES): before=%.1f after=%.1f (%.0f%% fewer)".format(
            before.toDouble() / SAMPLES, after.toDouble() / SAMPLES, 100.0 * (before - after) / before))
    }

    @Test fun cacheHitCost() {
        val keys = 100 // under PlaylistArtCache.MAX_ENTRIES, so nothing here is evicted
        for (i in 0 until keys) cachedPlaylistArtRenderModel(hues[i], seeds[i], sizePx)

        // Hits are far below what a per-call nanoTime can resolve, so time them in bulk.
        repeat(20_000) { cachedPlaylistArtRenderModel(hues[it % keys], seeds[it % keys], sizePx) }
        val lookups = 200_000
        val start = System.nanoTime()
        for (it in 0 until lookups) cachedPlaylistArtRenderModel(hues[it % keys], seeds[it % keys], sizePx)
        val hitNs = (System.nanoTime() - start).toDouble() / lookups
        Log.i(TAG, "%-42s mean=%8.3f us  (n=%d bulk lookups)".format("cache hit", hitNs / 1_000.0, lookups))
        // What was timed was a real hit: the held model comes back, not a rebuilt one.
        assertSame(cachedPlaylistArtRenderModel(hues[0], seeds[0], sizePx), cachedPlaylistArtRenderModel(hues[0], seeds[0], sizePx))
    }

    @Test fun drawPassCost() {
        // Cold: the first draw of a model also creates each lit brush's gradient shader, which a
        // cached model keeps. Warm: the same model drawn again, i.e. what a cache hit pays.
        val models = (0 until SAMPLES + WARMUP).map { playlistArtRenderModel(hues[it], seeds[it], sizePx) }
        val coldRecord = timeEach { recordIntoRenderNode(models[it]) }
        report("record, cold model (first draw)", coldRecord)
        // Every model has now been drawn once, so from here on the shaders exist.
        val oldStyle = (0 until SAMPLES + WARMUP).map { unlitSegments(seeds[it], sizePx.toInt()) }
        // Same warm models, single-path draw vs per-segment draw, alternating which goes first so
        // neither systematically inherits the other's cache/JIT state.
        val (newRecord, oldRecord) = timePair(
            { recordIntoRenderNode(models[it]) },
            { recordIntoRenderNode(models[it]) { drawReference(models[it], oldStyle[it]) } },
        )
        report("record, warm, single-path backbone (new)", newRecord)
        report("record, warm, per-segment lines (old)", oldRecord)
        val (newRaster, oldRaster) = timePair(
            { rasterise(models[it]) },
            { rasterise(models[it]) { drawReference(models[it], oldStyle[it]) } },
        )
        report("raster, warm, single-path backbone (new)", newRaster)
        report("raster, warm, per-segment lines (old)", oldRaster)
        // What was timed really draws: a rendered cover is not blank.
        val image = rasterise(models[0])
        val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
        assertTrue("the draw pass painted nothing", pixels.any { it != 0 })
        // What a row scrolling back into view pays: before = build the model, then draw it cold;
        // after = a cache lookup, then draw it warm.
        val build = median(timeEach { playlistArtRenderModel(hues[it], seeds[it] + 5, sizePx) })
        Log.i(TAG, "row entry, before (build %.0f + cold record %.0f) = %.0f us; after, cache hit (lookup + warm record) = %.0f us".format(
            build, median(coldRecord), build + median(coldRecord), median(newRecord)))
    }

    @Test fun cacheMissOverheadVersusBareBuild() {
        // A miss stores what it builds, so it keeps ~50 brushes and a few paths alive per entry
        // instead of dropping them at once. Separate that retention (GC) cost from the cache's own
        // bookkeeping by retaining into a plain ring buffer of the same size with no cache at all.
        // All three are timed after the same warmup: timing a "miss" first, on a cold JIT, once made
        // the cache look ~350us slower than it is.
        val ring = arrayOfNulls<PlaylistArtRenderModel>(PlaylistArtCache.MAX_ENTRIES)
        val firstHeld = cachedPlaylistArtRenderModel(hues[0], seeds[0] + 1, sizePx)
        report("build, result discarded", timeEach { playlistArtRenderModel(hues[it], seeds[it] + 1, sizePx) })
        report("build, result retained in ring buffer", timeEach { ring[it % ring.size] = playlistArtRenderModel(hues[it], seeds[it] + 1, sizePx) })
        report("build via cache (build + put + evict)", timeEach { cachedPlaylistArtRenderModel(hues[it], seeds[it] + 1, sizePx) })
        // What was timed really evicts: far more than MAX_ENTRIES newer keys pushed the first out,
        // while the newest is still held.
        assertTrue(SAMPLES + WARMUP > 2 * PlaylistArtCache.MAX_ENTRIES)
        assertNotSame(firstHeld, cachedPlaylistArtRenderModel(hues[0], seeds[0] + 1, sizePx))
        val newest = SAMPLES + WARMUP - 1
        assertSame(
            cachedPlaylistArtRenderModel(hues[newest], seeds[newest] + 1, sizePx),
            cachedPlaylistArtRenderModel(hues[newest], seeds[newest] + 1, sizePx),
        )
    }

    private fun recordIntoRenderNode(model: PlaylistArtRenderModel, draw: DrawScope.() -> Unit = { drawPlaylistArt(model) }) {
        val side = sizePx.toInt()
        val node = RenderNode("PlaylistArtBench").apply { setPosition(0, 0, side, side) }
        val canvas = node.beginRecording(side, side)
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(canvas), Size(sizePx, sizePx), draw)
        } finally {
            node.endRecording()
        }
    }

    private fun rasterise(model: PlaylistArtRenderModel, draw: DrawScope.() -> Unit = { drawPlaylistArt(model) }): ImageBitmap {
        val side = sizePx.toInt()
        val image = ImageBitmap(side, side)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(image), Size(sizePx, sizePx), draw)
        return image
    }

    /** Times [SAMPLES] calls after [WARMUP] discarded ones; result is per-call microseconds. */
    private fun timeEach(block: (Int) -> Unit): DoubleArray {
        for (i in 0 until WARMUP) block(i)
        return DoubleArray(SAMPLES) { i ->
            val start = System.nanoTime()
            block(WARMUP + i)
            (System.nanoTime() - start) / 1_000.0
        }
    }

    /** Times two variants over the same inputs, swapping which runs first each call. */
    private fun timePair(a: (Int) -> Unit, b: (Int) -> Unit): Pair<DoubleArray, DoubleArray> {
        for (i in 0 until WARMUP) { a(i); b(i) }
        val ra = DoubleArray(SAMPLES)
        val rb = DoubleArray(SAMPLES)
        for (i in 0 until SAMPLES) {
            val idx = WARMUP + i
            if (i % 2 == 0) {
                ra[i] = timeOne { a(idx) }; rb[i] = timeOne { b(idx) }
            } else {
                rb[i] = timeOne { b(idx) }; ra[i] = timeOne { a(idx) }
            }
        }
        return ra to rb
    }

    private inline fun timeOne(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000.0
    }

    private fun median(us: DoubleArray) = us.sorted()[us.size / 2]

    private fun report(label: String, us: DoubleArray) {
        val sorted = us.sorted()
        Log.i(TAG, "%-42s median=%8.1f us  p90=%8.1f us  min=%8.1f us  (n=%d, %.2fx density, %.0f px)".format(
            label, sorted[sorted.size / 2], sorted[sorted.size * 9 / 10], sorted.first(), SAMPLES, density, sizePx))
    }

    private companion object {
        const val TAG = "PlaylistArtBench"
        const val WARMUP = 300
        const val SAMPLES = 500
    }
}
