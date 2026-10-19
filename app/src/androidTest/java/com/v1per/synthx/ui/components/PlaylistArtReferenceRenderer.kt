package com.v1per.synthx.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The cover drawn with a per-segment backbone rather than [drawPlaylistArt]'s single path: one
 * `drawLine` per unlit segment. Test-only; shared by the pixel-equivalence test (which checks the
 * two agree) and the benchmark (which times them side by side on the same model).
 *
 * Restates `UnlitSegmentColor` (white at alpha 0.1) and `DiamondAlpha` (0.5) rather than importing
 * them, so a change to either constant in production shows up as a mismatch to look at
 * deliberately, not silently followed.
 */
internal fun DrawScope.drawReference(model: PlaylistArtRenderModel, unlit: List<Pair<Offset, Offset>>) {
    drawRect(brush = model.background)
    unlit.forEach { (start, end) ->
        drawLine(
            color = Color.White.copy(alpha = 0.1f),
            start = start,
            end = end,
            strokeWidth = model.strokeWidth,
            cap = StrokeCap.Round,
        )
    }
    model.diamonds.forEach { drawPath(path = it, color = model.diamondColor, alpha = 0.5f) }
    model.lit.forEach { drawLine(brush = it.brush, start = it.start, end = it.end, strokeWidth = model.strokeWidth, cap = StrokeCap.Round) }
}

/** Every segment the walk did not light, scaled the way the model scales them. */
internal fun unlitSegments(seed: Long, sizePx: Int): List<Pair<Offset, Offset>> {
    val lit = generatePlaylistArt(seed).litSegments
    val scale = sizePx / PlaylistArtGeometry.REFERENCE_SIZE
    return PlaylistArtGeometry.segments.withIndex()
        .filter { it.index !in lit }
        .map { (_, s) -> Offset(s.x1 * scale, s.y1 * scale) to Offset(s.x2 * scale, s.y2 * scale) }
}
