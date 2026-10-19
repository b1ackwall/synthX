package com.v1per.synthx.ui.components

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure geometry and pattern generation for a playlist's structural cover art. Deliberately free
 * of Compose and Android types so it stays plain-JVM unit testable — [PlaylistArt] is the only
 * thing that turns this into pixels.
 *
 * The picture is a 6x6 grid of cells. Every cell carries four short diagonal "corner segments"
 * running from just inside each corner towards the cell centre; all 144 of them are always
 * drawn, forming a fixed faint backbone. A seeded random walk from a grid corner towards the
 * centre vertex, mirrored across a randomly chosen symmetry plane, then decides which of those
 * segments light up. Same seed, same picture, forever — nothing here reads the clock or a
 * global RNG.
 */

/** The plane the generated path is mirrored across. Also picks the background gradient angle. */
internal enum class SymmetryPlane {
    VERTICAL,
    HORIZONTAL,
    DIAG1,
    DIAG2,
    ;

    fun perpendicular(): SymmetryPlane = when (this) {
        VERTICAL -> HORIZONTAL
        HORIZONTAL -> VERTICAL
        DIAG1 -> DIAG2
        DIAG2 -> DIAG1
    }
}

/**
 * One corner segment's endpoints in [PlaylistArtGeometry.REFERENCE_SIZE] coordinate space:
 * ([x1], [y1]) sits near the cell corner, ([x2], [y2]) near the cell centre. Renderers scale
 * these to whatever edge length they are drawing at; nothing here depends on a pixel size.
 */
internal data class PlaylistArtSegment(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

/** The generated result: everything [PlaylistArt] needs beyond the hue. */
internal data class PlaylistArtPattern(
    val plane: SymmetryPlane,
    /** Indices into [PlaylistArtGeometry.segments] that are lit. Deduplicated by construction. */
    val litSegments: Set<Int>,
)

internal object PlaylistArtGeometry {
    const val ROWS = 6
    const val COLS = 6

    /** Corner offsets within a cell, in the order the segment list stores them: TL, TR, BL, BR. */
    internal val CORNERS = listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1)

    private const val CELL = 60f
    private const val START_FRACTION = 0.18f
    private const val END_FRACTION = 0.79f
    private const val HALF = CELL / 2f

    /** Corner-to-centre distance, i.e. the full length of a cell's half-diagonal. */
    private val FULL = sqrt(HALF * HALF + HALF * HALF)
    private val START_DISTANCE = FULL * START_FRACTION
    private val END_DISTANCE = FULL * END_FRACTION

    /**
     * The gap left between a segment's inner end and the cell centre, projected back onto the
     * axes. Reused verbatim as both the gap between adjacent cells and the outer margin, which
     * is what makes the whole grid read as evenly spaced rather than as six separate tiles.
     */
    private val Z = (FULL - END_DISTANCE) * sqrt(2f)
    private val MARGIN = Z
    private val STRIDE = CELL + Z

    /** Edge length of the square the coordinates in [segments] are expressed against. */
    val REFERENCE_SIZE = MARGIN * 2f + (COLS - 1) * STRIDE + CELL

    /** Stroke width for one segment at [REFERENCE_SIZE]; scaled proportionally when drawn. */
    const val REFERENCE_STROKE_WIDTH = 8f

    /**
     * All 144 segments (6 x 6 cells x 4 corners), in `segmentIndex` order. Identical for every
     * playlist — only which of them are lit varies — so this is computed once for the process.
     */
    val segments: List<PlaylistArtSegment> = buildList {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val originX = c * STRIDE
                val originY = r * STRIDE
                val centreX = originX + HALF
                val centreY = originY + HALF
                CORNERS.forEach { (dr, dc) ->
                    val cornerX = originX + dc * CELL
                    val cornerY = originY + dr * CELL
                    // Unit vector from this corner towards the cell centre.
                    val ux = (centreX - cornerX) / FULL
                    val uy = (centreY - cornerY) / FULL
                    add(
                        PlaylistArtSegment(
                            x1 = MARGIN + cornerX + ux * START_DISTANCE,
                            y1 = MARGIN + cornerY + uy * START_DISTANCE,
                            x2 = MARGIN + cornerX + ux * END_DISTANCE,
                            y2 = MARGIN + cornerY + uy * END_DISTANCE,
                        )
                    )
                }
            }
        }
    }

    /** Position of one cell corner in [segments]. Must match the build order above exactly. */
    fun segmentIndex(r: Int, c: Int, dr: Int, dc: Int): Int = ((r * COLS) + c) * 4 + dr * 2 + dc

    /** True when every one of cell ([r], [c])'s four corners is lit — the filled-diamond case. */
    fun isFullyLit(pattern: PlaylistArtPattern, r: Int, c: Int): Boolean =
        CORNERS.all { (dr, dc) -> segmentIndex(r, c, dr, dc) in pattern.litSegments }
}

/**
 * The whole picture for one playlist, derived entirely from [structureSeed].
 *
 * The walk is mirrored once across its own plane; if that still leaves one of the four
 * quadrants empty (a path that never crossed the halfway line), it is spread further so the
 * cover always fills its frame: across the perpendicular axis for the axis-aligned planes, or
 * across all eight symmetries of the square for the diagonal ones (see the `when` below for
 * why the perpendicular diagonal cannot do this job).
 */
internal fun generatePlaylistArt(structureSeed: Long): PlaylistArtPattern {
    val random = Random(structureSeed)
    val (plane, lines) = buildPath(random)

    val mirrored = lines.map { it.mirroredAcross(plane) }
    var all = lines + mirrored
    if (all.mapTo(mutableSetOf(), PathLine::quadrant).size < QUADRANTS) {
        all = when (plane) {
            // Each axis mirror swaps the covered pair of quadrants with the other pair, so the
            // perpendicular axis always reaches whatever the first mirror could not.
            SymmetryPlane.VERTICAL, SymmetryPlane.HORIZONTAL -> {
                val other = plane.perpendicular()
                all + lines.map { it.mirroredAcross(other) } + mirrored.map { it.mirroredAcross(other) }
            }
            // A diagonal mirror fixes two quadrants outright and its perpendicular diagonal
            // fixes the same two, so neither can ever leave the pair the walk started in. Only
            // an axis reflection or a quarter turn moves them; take the whole orbit so the
            // result stays symmetric under every plane instead of just the two it was built on.
            SymmetryPlane.DIAG1, SymmetryPlane.DIAG2 -> lines.flatMap { it.d4Orbit() }
        }
    }

    return PlaylistArtPattern(
        plane = plane,
        // Duplicates are expected and meaningless — a segment is lit or it is not.
        litSegments = all.mapTo(mutableSetOf()) {
            PlaylistArtGeometry.segmentIndex(it.r, it.c, it.dr, it.dc)
        },
    )
}

/** How many walk steps to allow before giving up; a path to the centre needs far fewer. */
private const val WALK_STEP_LIMIT = 60

/**
 * Probability of taking the greediest (most centre-ward) exit rather than any of the three. The
 * "any of the three" branch includes the greedy option, so the effective greedy rate is
 * 0.4 + 0.6/3 = 60%. Measured over 200k seeds: 0.7 with a forced centre exit gave
 * ~260 effectively distinct covers and diamonds on 23% of them; 0.4 with no forced exit gives
 * ~1,800 and ~54%. Going lower stops helping variety (0.0 is worse than 0.3) while covers get
 * steadily fuller, so do not push it further without re-measuring.
 */
private const val GREEDY_EXIT_PROBABILITY = 0.4f

private const val QUADRANTS = 4

/** One lit corner of one cell: cell ([r], [c]), corner ([dr], [dc]) with both in 0..1. */
private data class PathLine(val r: Int, val c: Int, val dr: Int, val dc: Int) {
    /** Which of the four quadrants of the grid this cell falls in, as a 0..3 key. */
    fun quadrant(): Int =
        (if (r < PlaylistArtGeometry.ROWS / 2) 0 else 1) * 2 +
            (if (c < PlaylistArtGeometry.COLS / 2) 0 else 1)

    /**
     * Reflects both the cell and the vertex this corner points at, then re-derives the corner
     * from the difference. The two reflections use different extents on purpose: vertices run
     * 0..COLS while cells only run 0..COLS-1.
     */
    fun mirroredAcross(plane: SymmetryPlane): PathLine {
        val vertex = mirrorPoint(r + dr, c + dc, plane, PlaylistArtGeometry.COLS)
        val cell = mirrorPoint(r, c, plane, PlaylistArtGeometry.COLS - 1)
        return PathLine(cell.first, cell.second, vertex.first - cell.first, vertex.second - cell.second)
    }
}

/**
 * Every image of this line under the eight symmetries of the square: the identity, the three
 * rotations, and the four reflections (both axes, both diagonals). Built as the closure of two
 * generating reflections rather than by naming all eight compositions, so it cannot drift out
 * of step with [mirroredAcross].
 */
private fun PathLine.d4Orbit(): Set<PathLine> {
    var orbit = setOf(this)
    while (true) {
        val grown = orbit + orbit.flatMap { line ->
            listOf(SymmetryPlane.VERTICAL, SymmetryPlane.DIAG1).map(line::mirroredAcross)
        }
        if (grown.size == orbit.size) return orbit
        orbit = grown
    }
}

private data class GridPath(val plane: SymmetryPlane, val lines: List<PathLine>)

/** A cell corner touching some vertex: cell ([r], [c]), reached at corner ([dr], [dc]). */
private data class CellCorner(val r: Int, val c: Int, val dr: Int, val dc: Int)

private data class ExitOption(
    val dr: Int,
    val dc: Int,
    val vertexR: Int,
    val vertexC: Int,
    /** Manhattan distance from this exit's vertex to the grid's centre vertex. */
    val distance: Int,
)

private fun mirrorPoint(r: Int, c: Int, plane: SymmetryPlane, maxIndex: Int): Pair<Int, Int> =
    when (plane) {
        SymmetryPlane.VERTICAL -> r to (maxIndex - c)
        SymmetryPlane.HORIZONTAL -> (maxIndex - r) to c
        SymmetryPlane.DIAG1 -> c to r
        SymmetryPlane.DIAG2 -> (maxIndex - c) to (maxIndex - r)
    }

/** The (up to four) cells touching a vertex, each tagged with the corner that touches it. */
private fun cellsAtVertex(vertexR: Int, vertexC: Int): List<CellCorner> =
    PlaylistArtGeometry.CORNERS.mapNotNull { (dr, dc) ->
        val r = vertexR - dr
        val c = vertexC - dc
        if (r in 0 until PlaylistArtGeometry.ROWS && c in 0 until PlaylistArtGeometry.COLS) {
            CellCorner(r, c, dr, dc)
        } else {
            null
        }
    }

/**
 * Cells sitting *on* a diagonal mirror line are their own reflection, so stepping onto one
 * would fold the path back over itself instead of widening it. Only the diagonal planes cut
 * through cells this way; the axis-aligned ones cut between them.
 */
private fun isOnCuttingDiagonal(r: Int, c: Int, plane: SymmetryPlane, maxIndex: Int): Boolean =
    when (plane) {
        SymmetryPlane.DIAG1 -> r == c
        SymmetryPlane.DIAG2 -> r + c == maxIndex
        else -> false
    }

/**
 * Walks from a grid corner towards the centre vertex, one cell at a time. Each step enters a
 * cell at a known corner and leaves by one of the other three — usually the one closest to the
 * centre, occasionally any of them, which is what keeps the paths from all looking alike. Both
 * the entry and the exit corner are lit.
 */
private fun buildPath(random: Random): GridPath {
    val planes = SymmetryPlane.entries
    val plane = planes[random.nextInt(planes.size)]
    val maxR = PlaylistArtGeometry.ROWS - 1
    val maxC = PlaylistArtGeometry.COLS - 1

    // Starting from a corner the chosen plane does not immediately reflect onto itself.
    val startOptions = when (plane) {
        SymmetryPlane.VERTICAL, SymmetryPlane.HORIZONTAL ->
            listOf(0 to 0, maxR to 0, 0 to maxC, maxR to maxC)
        SymmetryPlane.DIAG1 -> listOf(0 to maxC, maxR to 0)
        SymmetryPlane.DIAG2 -> listOf(0 to 0, maxR to maxC)
    }
    var (cellR, cellC) = startOptions[random.nextInt(startOptions.size)]
    // Enter through the corner facing out of the grid, so the path reads as coming from outside.
    var entryDr = if (cellR == 0) 0 else 1
    var entryDc = if (cellC == 0) 0 else 1

    val centreVertexR = PlaylistArtGeometry.ROWS / 2
    val centreVertexC = PlaylistArtGeometry.COLS / 2

    val lines = mutableListOf<PathLine>()
    var step = 0
    while (step < WALK_STEP_LIMIT) {
        step++
        val options = PlaylistArtGeometry.CORNERS
            .filterNot { (dr, dc) -> dr == entryDr && dc == entryDc }
            .map { (dr, dc) ->
                val vertexR = cellR + dr
                val vertexC = cellC + dc
                ExitOption(
                    dr = dr,
                    dc = dc,
                    vertexR = vertexR,
                    vertexC = vertexC,
                    distance = abs(vertexR - centreVertexR) + abs(vertexC - centreVertexC),
                )
            }
            // Stable sort, so equally central options keep their TL, TR, BL, BR ordering.
            .sortedBy { it.distance }

        // No special case for an exit that lands on the centre: it is gambled like any other, so
        // the walk ends there only when selection happens to pick it (see the break below).
        val exit = if (random.nextFloat() < GREEDY_EXIT_PROBABILITY) {
            options[0]
        } else {
            options[random.nextInt(options.size)]
        }

        lines += PathLine(cellR, cellC, entryDr, entryDc)
        lines += PathLine(cellR, cellC, exit.dr, exit.dc)
        if (exit.vertexR == centreVertexR && exit.vertexC == centreVertexC) break

        val candidates = cellsAtVertex(exit.vertexR, exit.vertexC)
            .filterNot { it.r == cellR && it.c == cellC }
            .filterNot { isOnCuttingDiagonal(it.r, it.c, plane, maxR) }
        if (candidates.isEmpty()) break

        val next = candidates[random.nextInt(candidates.size)]
        cellR = next.r
        cellC = next.c
        entryDr = next.dr
        entryDc = next.dc
    }

    return GridPath(plane, lines)
}
