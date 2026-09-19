package com.v1per.synthx.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [PlaylistArtCache] is generic so it can be exercised here with a cheap stand-in value instead
 * of a Compose `Brush`/`Path` model, which needs the Android graphics stack. The production
 * instance ([cachedPlaylistArtRenderModel]) uses exactly this class.
 */
class PlaylistArtCacheTest {

    private class Built(val hue: Int, val seed: Long, val sizePx: Float)

    /** A cache whose builder counts its own calls, so "built once" can be asserted directly. */
    private class Counting(maxEntries: Int) {
        var builds = 0
        val cache = PlaylistArtCache(maxEntries) { hue, seed, sizePx ->
            builds++
            Built(hue, seed, sizePx)
        }
    }

    @Test
    fun `asking twice for the same key builds once and hands back the same instance`() {
        val counting = Counting(maxEntries = 8)
        val first = counting.cache.get(120, 42L, 105f)
        val second = counting.cache.get(120, 42L, 105f)

        assertSame(first, second)
        assertEquals(1, counting.builds)
    }

    @Test
    fun `hue, seed and size each distinguish a key on their own`() {
        val counting = Counting(maxEntries = 8)
        val base = counting.cache.get(120, 42L, 105f)

        assertNotSame(base, counting.cache.get(124, 42L, 105f), "hue must be part of the key")
        assertNotSame(base, counting.cache.get(120, 43L, 105f), "seed must be part of the key")
        assertNotSame(base, counting.cache.get(120, 42L, 110f), "size must be part of the key")
        assertEquals(4, counting.builds)
        // ...and each of them is now remembered under its own key rather than displacing the others.
        assertSame(base, counting.cache.get(120, 42L, 105f))
        assertEquals(4, counting.builds)
    }

    @Test
    fun `the built value receives exactly the key it was asked for`() {
        val built = Counting(maxEntries = 4).cache.get(200, -7L, 87.5f)

        assertEquals(200, built.hue)
        assertEquals(-7L, built.seed)
        assertEquals(87.5f, built.sizePx)
    }

    @Test
    fun `the cache never holds more than its cap and drops the least recently used first`() {
        val counting = Counting(maxEntries = 3)
        val a = counting.cache.get(0, 1L, 100f)
        counting.cache.get(0, 2L, 100f)
        counting.cache.get(0, 3L, 100f)
        assertEquals(3, counting.cache.size)

        // Fourth key: something has to go, and it is `a`, the oldest untouched entry.
        counting.cache.get(0, 4L, 100f)
        assertEquals(3, counting.cache.size)
        assertEquals(4, counting.builds)

        val rebuilt = counting.cache.get(0, 1L, 100f)
        assertNotSame(a, rebuilt, "the evicted entry should have been rebuilt, not resurrected")
        assertEquals(5, counting.builds)
    }

    @Test
    fun `reading an entry protects it from eviction`() {
        val counting = Counting(maxEntries = 3)
        val a = counting.cache.get(0, 1L, 100f)
        counting.cache.get(0, 2L, 100f)
        counting.cache.get(0, 3L, 100f)

        // Touch `a`, making `2` the least recently used, then overflow by one.
        assertSame(a, counting.cache.get(0, 1L, 100f))
        counting.cache.get(0, 4L, 100f)

        assertSame(a, counting.cache.get(0, 1L, 100f), "the recently read entry must have survived")
        val buildsBefore = counting.builds
        counting.cache.get(0, 2L, 100f)
        assertEquals(buildsBefore + 1, counting.builds, "the untouched entry is the one that was evicted")
    }

    @Test
    fun `the production cap comfortably holds a whole default library`() {
        // ~68 playlists ship in the default seed manifest, each with one (hue, seed) at one size.
        assertTrue(PlaylistArtCache.MAX_ENTRIES >= 100, "Cap ${PlaylistArtCache.MAX_ENTRIES} leaves little headroom over ~68 playlists")

        val counting = Counting(PlaylistArtCache.MAX_ENTRIES)
        (0 until 68).forEach { counting.cache.get((it * 4) % 360, it * 7919L, 105f) }
        (0 until 68).forEach { counting.cache.get((it * 4) % 360, it * 7919L, 105f) }
        assertEquals(68, counting.builds, "A second scroll through the library should rebuild nothing")
    }
}
