package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.sysml2.edge.EdgeLabelPart
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A placed, wrapped STM edge label: [lines] of styled parts centred on ([cx], [cy]) inside a [w] x [h] box. */
internal data class StmLabelBlock(
    val lines: List<List<EdgeLabelPart>>,
    val fullText: String,
    val truncated: Boolean,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
)

/**
 * Deterministic placement of SysML 2 state-transition labels. Text is wrapped (trigger, guard and
 * effect on one line up to 180 px, otherwise one part per line, at most three lines, long parts
 * truncated with an ellipsis). Candidate positions sit at 50, 30 and 70 percent of the path, left
 * and right of it; the first without collision wins, otherwise the smallest overlap (ties: lowest
 * candidate index). Edges are processed in id order, so output never depends on map iteration.
 */
internal object StmLabelPlacer {
    private const val CHAR_W = 7.2f
    private const val MAX_LINE_PX = 180f
    private const val LINE_H = 12f
    private const val PAD = 2f
    private const val GAP = 6f
    private const val MAX_PART_CHARS = 28
    private const val COLLISION_CHECK_LIMIT = 500
    private val FRACTIONS = listOf(0.5f, 0.3f, 0.7f)

    /** Wraps [parts] into at most three lines; the flag tells whether any text was cut. */
    fun wrap(parts: List<EdgeLabelPart>): Pair<List<List<EdgeLabelPart>>, Boolean> {
        if (parts.isEmpty()) return emptyList<List<EdgeLabelPart>>() to false
        val joined = parts.joinToString(" ") { it.text }
        if (joined.length * CHAR_W <= MAX_LINE_PX) return listOf(parts) to false
        var cut = false
        val lines =
            parts.take(3).map { p ->
                if (p.text.length > MAX_PART_CHARS) {
                    cut = true
                    listOf(p.copy(text = p.text.take(MAX_PART_CHARS - 1) + "…"))
                } else {
                    listOf(p)
                }
            }
        return lines to cut
    }

    /** Number of text lines [parts] wrap to (used for node sizing). */
    fun lineCount(parts: List<EdgeLabelPart>): Int = wrap(parts).first.size

    fun place(
        edges: List<Triple<String, EdgeRoute, List<EdgeLabelPart>>>,
        obstacles: List<Rect>,
    ): Map<String, StmLabelBlock> {
        val out = LinkedHashMap<String, StmLabelBlock>()
        val placed = ArrayList<Box>()
        val obs =
            obstacles.map {
                Box(
                    x0 = it.origin.x,
                    y0 = it.origin.y,
                    x1 = it.origin.x + it.size.width,
                    y1 =
                        it.origin.y + it.size.height,
                )
            }
        val check = edges.size <= COLLISION_CHECK_LIMIT
        for ((id, route, parts) in edges.sortedBy { it.first }) {
            val (lines, cut) = wrap(parts)
            if (lines.isEmpty()) continue
            val chars = lines.maxOf { l -> l.sumOf { it.text.length } + (l.size - 1) }
            val w = chars * CHAR_W + 2 * PAD
            val h = lines.size * LINE_H + 2 * PAD
            val pts = points(route)
            val cands = candidates(pts = pts, w = w, h = h)
            var best = cands.first()
            if (check) {
                var bestArea = Float.MAX_VALUE
                for (c in cands) {
                    val a = overlap(b = c, others = obs) + overlap(b = c, others = placed)
                    if (a == 0f) {
                        best = c
                        break
                    }
                    if (a < bestArea) {
                        bestArea = a
                        best = c
                    }
                }
            }
            placed += best
            out[id] =
                StmLabelBlock(
                    lines = lines,
                    fullText = parts.joinToString(" ") { it.text },
                    truncated = cut,
                    cx = (best.x0 + best.x1) / 2f,
                    cy = (best.y0 + best.y1) / 2f,
                    w = w,
                    h = h,
                )
        }
        return out
    }

    private data class Box(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    )

    private fun overlap(
        b: Box,
        others: List<Box>,
    ): Float {
        var sum = 0f
        for (o in others) {
            val dx = min(b.x1, o.x1) - max(b.x0, o.x0)
            val dy = min(b.y1, o.y1) - max(b.y0, o.y0)
            if (dx > 0f && dy > 0f) sum += dx * dy
        }
        return sum
    }

    private fun points(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }

    private fun candidates(
        pts: List<Point>,
        w: Float,
        h: Float,
    ): List<Box> {
        val lens = (0 until pts.size - 1).map { i -> dist(a = pts[i], b = pts[i + 1]) }
        val total = lens.sum()
        val out = ArrayList<Box>()
        for (f in FRACTIONS) {
            var target = total * f
            var seg = 0
            while (seg < lens.size - 1 && target > lens[seg]) {
                target -= lens[seg]
                seg++
            }
            val a = pts[seg]
            val b = pts[min(seg + 1, pts.size - 1)]
            val len = max(lens.getOrElse(seg) { 0f }, 0.001f)
            val t = (target / len).coerceIn(0f, 1f)
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            val nx = -(b.y - a.y) / len
            val ny = (b.x - a.x) / len
            val ext = abs(nx) * w / 2f + abs(ny) * h / 2f
            for (side in listOf(-1f, 1f)) {
                val cx = px + nx * side * (GAP + ext)
                val cy = py + ny * side * (GAP + ext)
                out += Box(x0 = cx - w / 2f, y0 = cy - h / 2f, x1 = cx + w / 2f, y1 = cy + h / 2f)
            }
        }
        return out
    }

    private fun dist(
        a: Point,
        b: Point,
    ): Float = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
}
