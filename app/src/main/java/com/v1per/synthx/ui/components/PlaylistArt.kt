package com.v1per.synthx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * A playlist's generated cover art, drawn live from the two values stored on the row — never
 * rasterised to a file. The picture is completely static once generated, so everything that
 * costs an allocation (brushes, diamond paths, scaled coordinates) is built once per
 * hue/seed/size and the draw pass only issues commands.
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
    val model = remember(hue, structureSeed, sizePx) {
        playlistArtRenderModel(hue, structureSeed, sizePx)
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

/** Faint always-on backbone, drawn for every segment the walk did not light. */
private val UnlitSegmentColor = Color.White.copy(alpha = 0.1f)

/** Kept well below opaque so a filled cell reads as a glow between its four strokes. */
private const val DiamondAlpha = 0.5f

/**
 * Outer endpoints in TL, TR, BR, BL winding order — deliberately not
 * [PlaylistArtGeometry.CORNERS]' TL, TR, BL, BR storage order, which would trace a bow tie.
 */
private val DiamondWinding = listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)

private class ScaledSegment(val start: Offset, val end: Offset)

private class LitSegment(val start: Offset, val end: Offset, val brush: Brush)

private class PlaylistArtRenderModel(
    val background: Brush,
    val strokeWidth: Float,
    val unlit: List<ScaledSegment>,
    val lit: List<LitSegment>,
    val diamonds: List<Path>,
    val diamondColor: Color,
)

private fun playlistArtRenderModel(
    hue: Int,
    structureSeed: Long,
    sizePx: Float,
): PlaylistArtRenderModel {
    val pattern = generatePlaylistArt(structureSeed)
    val scale = sizePx / PlaylistArtGeometry.REFERENCE_SIZE
    val hueDegrees = hue.toFloat()

    val litStart = Color.hsl(hueDegrees, 0.5f, 0.2f)
    val litEnd = Color.hsl(hueDegrees, 0.8f, 0.8f)

    val unlit = mutableListOf<ScaledSegment>()
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
            unlit += ScaledSegment(start, end)
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
        unlit = unlit,
        lit = lit,
        diamonds = diamonds,
        diamondColor = Color.hsl(hueDegrees, 0.3f, 0.5f),
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

private fun DrawScope.drawPlaylistArt(model: PlaylistArtRenderModel) {
    drawRect(brush = model.background)
    model.unlit.forEach { segment ->
        drawLine(
            color = UnlitSegmentColor,
            start = segment.start,
            end = segment.end,
            strokeWidth = model.strokeWidth,
            cap = StrokeCap.Round,
        )
    }
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
