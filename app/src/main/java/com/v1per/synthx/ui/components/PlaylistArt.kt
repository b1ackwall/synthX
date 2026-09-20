package com.v1per.synthx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.collection.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.v1per.synthx.data.playlistart.DiamondRole
import com.v1per.synthx.data.playlistart.LitEndRole
import com.v1per.synthx.data.playlistart.LitStartRole
import com.v1per.synthx.data.playlistart.PlaylistArtRole

/**
 * A playlist's generated cover art, drawn live from the two values stored on the row — never
 * rasterised to a file. The picture is completely static once generated, so everything that
 * costs an allocation (brushes, diamond paths, the backbone path, scaled coordinates) is built
 * once per hue/seed/size and the draw pass only issues commands. "Once" is per process, not per
 * composition: every call site is a lazy-list item, so `remember` alone would rebuild the model
 * each time a row scrolls off and back — see [PlaylistArtModelCache].
 *
 * A playlist with no art yet — a row that predates the v13->v14 schema and has not met
 * PlaylistArtBackfill, or one seeded by PlaylistSeedBootstrapper — falls back to the same flat
 * [ArtPlaceholder] every other artless surface uses, rather than inventing a look of its own.
 */
@Composable
internal fun PlaylistArt(
    hue: Int?,
    structureSeed: Long?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    if (hue == null || structureSeed == null || hue !in 0..359) {
        // Out-of-range hue (hand-edited row, restore from a divergent writer) falls back the
        // same as missing art rather than crashing Color.hsl, which requires 0f..360f.
        ArtPlaceholder(size = size, modifier = modifier)
        return
    }

    val sizePx = with(LocalDensity.current) { size.toPx() }
    // remember still earns its keep: it spares recomposition the cache's lock. The cache is what
    // survives the item leaving and re-entering composition.
    val model = remember(hue, structureSeed, sizePx) {
        cachedPlaylistArtRenderModel(hue, structureSeed, sizePx)
    }

    Canvas(
        modifier = modifier
            .size(size)
            // Same 2dp corner as the placeholder this replaces, so a mixed list stays flush.
            .clip(MaterialTheme.shapes.small),
    ) {
        drawPlaylistArt(model)
    }
}

/**
 * This role's colour at [hue]: fixed saturation, with HSL lightness chosen so the colour has the
 * same perceived (OkLab) lightness at every hue instead of the same HSL lightness — which at a
 * flat `hsl(h, s, 0.8)` makes yellow covers far brighter than blue ones.
 */
private fun PlaylistArtRole.colorAt(hue: Float): Color = Color.hsl(hue, saturation, hslLightness(hue))

/** Faint always-on backbone, drawn for every segment the walk did not light. */
private val UnlitSegmentColor = Color.White.copy(alpha = 0.1f)

/** Kept well below opaque so a filled cell reads as a glow between its four strokes. */
private const val DiamondAlpha = 0.5f

/**
 * Outer endpoints in TL, TR, BR, BL winding order — deliberately not
 * [PlaylistArtGeometry.CORNERS]' TL, TR, BL, BR storage order, which would trace a bow tie.
 */
private val DiamondWinding = listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)

internal class LitSegment(val start: Offset, val end: Offset, val brush: Brush)

internal class PlaylistArtRenderModel(
    val background: Brush,
    val strokeWidth: Float,
    /**
     * Every segment the walk did not light, as one path of two-point subpaths. They all share
     * [UnlitSegmentColor], [strokeWidth] and a round cap, and no two come closer than the stroke
     * is wide, so one stroked path paints exactly what ~100 separate lines did.
     */
    val backbone: Path,
    val lit: List<LitSegment>,
    val diamonds: List<Path>,
    val diamondColor: Color,
)

internal fun playlistArtRenderModel(
    hue: Int,
    structureSeed: Long,
    sizePx: Float,
): PlaylistArtRenderModel {
    val pattern = generatePlaylistArt(structureSeed)
    val scale = sizePx / PlaylistArtGeometry.REFERENCE_SIZE
    val hueDegrees = hue.toFloat()

    val litStart = LitStartRole.colorAt(hueDegrees)
    val litEnd = LitEndRole.colorAt(hueDegrees)

    val backbone = Path()
    val lit = mutableListOf<LitSegment>()
    PlaylistArtGeometry.segments.forEachIndexed { index, segment ->
        val start = Offset(segment.x1 * scale, segment.y1 * scale)
        val end = Offset(segment.x2 * scale, segment.y2 * scale)
        if (index in pattern.litSegments) {
            // Dark at the corner, bright at the centre — the gradient runs along the segment
            // itself, which is what gives the pattern its sense of direction.
            lit += LitSegment(
                start = start,
                end = end,
                brush = Brush.linearGradient(listOf(litStart, litEnd), start = start, end = end),
            )
        } else {
            backbone.moveTo(start.x, start.y)
            backbone.lineTo(end.x, end.y)
        }
    }

    val diamonds = buildList {
        for (r in 0 until PlaylistArtGeometry.ROWS) {
            for (c in 0 until PlaylistArtGeometry.COLS) {
                if (!PlaylistArtGeometry.isFullyLit(pattern, r, c)) continue
                add(diamondPath(r, c, scale))
            }
        }
    }

    return PlaylistArtRenderModel(
        background = backgroundBrush(hueDegrees, pattern.plane, sizePx),
        // Scaled with the geometry, then floored at a pixel so the backbone cannot thin out to
        // nothing at small sizes on low-density screens.
        strokeWidth = (PlaylistArtGeometry.REFERENCE_STROKE_WIDTH * scale).coerceAtLeast(1f),
        backbone = backbone,
        lit = lit,
        diamonds = diamonds,
        diamondColor = DiamondRole.colorAt(hueDegrees),
    )
}

/** The quad joining the four inner segment ends of a fully lit cell. */
private fun diamondPath(r: Int, c: Int, scale: Float): Path = Path().apply {
    DiamondWinding.forEachIndexed { index, (dr, dc) ->
        val segment =
            PlaylistArtGeometry.segments[PlaylistArtGeometry.segmentIndex(r, c, dr, dc)]
        val x = segment.x2 * scale
        val y = segment.y2 * scale
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Dark, mid, dark in the playlist's own hue, angled by the symmetry plane the pattern was
 * built around so the light in the background runs with the structure rather than against it.
 */
private fun backgroundBrush(hueDegrees: Float, plane: SymmetryPlane, sizePx: Float): Brush {
    val colors = listOf(
        Color.hsl(hueDegrees, 0.05f, 0.10f),
        Color.hsl(hueDegrees, 0.20f, 0.30f),
        Color.hsl(hueDegrees, 0.05f, 0.10f),
    )
    val half = sizePx / 2f
    val (start, end) = when (plane) {
        // CSS 90deg / 180deg / 45deg / 135deg respectively.
        SymmetryPlane.VERTICAL -> Offset(0f, half) to Offset(sizePx, half)
        SymmetryPlane.HORIZONTAL -> Offset(half, 0f) to Offset(half, sizePx)
        SymmetryPlane.DIAG1 -> Offset(0f, sizePx) to Offset(sizePx, 0f)
        SymmetryPlane.DIAG2 -> Offset(0f, 0f) to Offset(sizePx, sizePx)
    }
    return Brush.linearGradient(colors, start = start, end = end)
}

internal fun DrawScope.drawPlaylistArt(model: PlaylistArtRenderModel) {
    drawRect(brush = model.background)
    drawPath(
        path = model.backbone,
        color = UnlitSegmentColor,
        style = Stroke(width = model.strokeWidth, cap = StrokeCap.Round),
    )
    // Under the lit strokes, so their bright inner ends stay crisp on top of the fill.
    model.diamonds.forEach { path ->
        drawPath(path = path, color = model.diamondColor, alpha = DiamondAlpha)
    }
    model.lit.forEach { segment ->
        drawLine(
            brush = segment.brush,
            start = segment.start,
            end = segment.end,
            strokeWidth = model.strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * The finished model for ([hue], [structureSeed], [sizePx]), built at most once per process for
 * as long as it stays among the most recently used [PlaylistArtCache.MAX_ENTRIES].
 */
internal fun cachedPlaylistArtRenderModel(hue: Int, structureSeed: Long, sizePx: Float): PlaylistArtRenderModel =
    PlaylistArtModelCache.cache.get(hue, structureSeed, sizePx)

/**
 * Process-wide home for [PlaylistArtRenderModel]s. Deliberately a plain object rather than
 * anything wired through a ViewModel factory: it is a pure function of its key (nothing here reads
 * a composition local, a theme or a clock), so it has no lifecycle to manage and nothing that
 * could go stale — the same reasoning as `PlaybackCacheRevision` and the other process-level
 * objects in `data/`.
 */
private object PlaylistArtModelCache {
    val cache = PlaylistArtCache(PlaylistArtCache.MAX_ENTRIES, ::playlistArtRenderModel)
}

/**
 * A small least-recently-used cache from (hue, seed, size) to whatever [build] makes of them.
 * Generic in the value so eviction and key equality can be unit-tested on the plain JVM without
 * constructing a Compose `Brush` or `Path`.
 *
 * Sized by entry count. Each playlist has exactly one (hue, seed), and there are two call sites:
 * the 40dp list row and the 160dp detail-screen cover. The size is part of the key, so the two
 * never share or displace each other's entries. The row is the only one drawn for every
 * playlist, so the number of live row keys is the number of playlists — about 68 in the default
 * library — and the detail screen adds at most one more key per playlist actually opened.
 * 128 leaves headroom before a scrolled-back row ever has to rebuild.
 *
 * [androidx.collection.LruCache] is plain Kotlin (no Android framework types) and synchronised,
 * which matters because lazy-list prefetch and ordinary composition can both ask. [get] builds
 * outside any lock: two callers racing on the same missing key may each build once and the later
 * put wins, which is harmless because the value is immutable and equal either way.
 */
internal class PlaylistArtCache<V : Any>(
    maxEntries: Int,
    private val build: (hue: Int, structureSeed: Long, sizePx: Float) -> V,
) {
    private data class Key(val hue: Int, val structureSeed: Long, val sizePx: Float)

    private val entries = LruCache<Key, V>(maxEntries)

    fun get(hue: Int, structureSeed: Long, sizePx: Float): V {
        val key = Key(hue, structureSeed, sizePx)
        entries[key]?.let { return it }
        val built = build(hue, structureSeed, sizePx)
        entries.put(key, built)
        return built
    }

    val size: Int get() = entries.size()

    companion object {
        const val MAX_ENTRIES = 128
    }
}
