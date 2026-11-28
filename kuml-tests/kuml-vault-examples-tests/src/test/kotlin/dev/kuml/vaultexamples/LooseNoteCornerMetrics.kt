@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.max
import kotlin.math.min

/** Violation counts of the free-standing notes of one diagram, see [LooseNoteCornerMetrics]. */
internal data class LooseCornerViolations(
    val l1: Int,
    val l2: Int,
    val l2b: Int,
) {
    override fun toString(): String = "L1=$l1 L2=$l2 L2b=$l2b"
}

/**
 * Report of one diagram. [area] is the image area (width times height), [areaWithoutNote] the same for the source without its
 * free-standing notes (null when not rendered). [l1] is true when a free-standing note lies outside the content box although the
 * corner search of PAL wave Y3 finds a free place inside it.
 */
internal data class LooseCornerReport(
    val area: Double,
    val areaWithoutNote: Double?,
    val l1: Boolean,
    val l2: Int,
    val l2b: Int,
    val noteIds: List<String>,
)

/**
 * PAL Welle Y3 detector: free-standing notes (no `note-link:` anchor line) and the free bottom-right corner of the content box.
 * Works on the rendered SVG with rectangles and polylines only (no glyph metrics).
 *
 * - L1 a free-standing note lies outside the bounding box of all non-note elements although the corner search finds a free place
 *   inside it (12 px clearance, 16 px inset to the lane border).
 * - L2 the note rectangle really intersects a foreign element: node, edge segment, edge label, lane title, lane border line.
 * - L2b pure figure: number of gaps below 12 px (nodes, edges, labels, titles, other notes) or below 16 px (lane border).
 */
internal object LooseNoteCornerMetrics {
    const val CLEARANCE = 12.0
    const val LANE_INSET = 16.0
    const val STEP = 8.0
    private const val EPS = 0.5

    /** A note that sticks out of the box by at most this much counts as inside (shape boxes of round nodes are read approximately). */
    private const val OUTSIDE_TOL = 2.0

    private val sizeRe = Regex("""<svg[^>]*\bviewBox="[-0-9.]+ [-0-9.]+ ([0-9.]+) ([0-9.]+)"""")

    fun imageArea(svg: String): Double {
        val m = sizeRe.find(svg) ?: return 0.0
        return m.groupValues[1].toDouble() * m.groupValues[2].toDouble()
    }

    /** Notes without an anchor line in the SVG. */
    fun freeNotes(svg: String): Map<String, SvgRect> {
        val anchored = NoteLeaderMetrics.leaders(svg).map { it.noteId }.toSet()
        return NoteLeaderMetrics.noteRects(svg).filterKeys { it !in anchored }
    }

    private class Foreign(
        val nodes: List<SvgRect>,
        val labels: List<SvgRect>,
        val titles: List<SvgRect>,
        val lanes: List<SvgRect>,
        val edges: List<List<Pair<Double, Double>>>,
    )

    private fun foreign(svg: String): Foreign {
        val titles =
            SvgGeometry
                .nodes(svg)
                .filter { it.id.startsWith("activityPartitionHeader") }
                .mapNotNull { n ->
                    val tag = Regex("""<rect\b[^>]*>""").find(n.body)?.value ?: return@mapNotNull null

                    fun a(name: String) =
                        Regex("""\b$name="([-0-9.]+)"""")
                            .find(tag)
                            ?.groupValues
                            ?.get(1)
                            ?.toDouble() ?: 0.0
                    SvgRect(n.tx + a("x"), n.ty + a("y"), a("width"), a("height"))
                }
        return Foreign(
            nodes = NoteLeaderMetrics.nodeRects(svg).values.toList(),
            labels = NoteLeaderMetrics.labelRects(svg).map { it.second },
            titles = titles,
            lanes = NoteLeaderMetrics.laneBoundaries(svg),
            edges = SvgGeometry.edgePolylines(svg).values.toList(),
        )
    }

    /** Bounding box of everything that is not a note. */
    private fun contentBox(f: Foreign): SvgRect? {
        var l = Double.MAX_VALUE
        var t = Double.MAX_VALUE
        var r = -Double.MAX_VALUE
        var b = -Double.MAX_VALUE

        fun add(
            x: Double,
            y: Double,
            x2: Double = x,
            y2: Double = y,
        ) {
            l = min(l, x)
            t = min(t, y)
            r = max(r, x2)
            b = max(b, y2)
        }
        (f.nodes + f.labels + f.titles + f.lanes).forEach { add(it.x, it.y, it.right, it.bottom) }
        f.edges.forEach { e -> e.forEach { add(it.first, it.second) } }
        return if (l > r) null else SvgRect(l, t, r - l, b - t)
    }

    private fun laneOk(
        r: SvgRect,
        lanes: List<SvgRect>,
    ): Boolean =
        lanes.isEmpty() ||
            lanes.any {
                r.x >= it.x + LANE_INSET &&
                    r.right <= it.right - LANE_INSET &&
                    r.y >= it.y + LANE_INSET &&
                    r.bottom <= it.bottom - LANE_INSET
            }

    private fun gapToForeign(
        r: SvgRect,
        f: Foreign,
        others: List<SvgRect>,
    ): Double {
        var best = Double.MAX_VALUE
        for (x in f.nodes + f.labels + f.titles + others) best = min(best, r.gapTo(x))
        for (e in f.edges) best = min(best, SvgGeometry.distance(e, r))
        return best
    }

    /** First free corner candidate (same sequence as the layout code), or null. */
    private fun cornerFree(
        size: SvgRect,
        box: SvgRect,
        f: Foreign,
        others: List<SvgRect>,
    ): Boolean {
        val m = if (f.lanes.isEmpty()) 0.0 else LANE_INSET
        val x0 = box.right - m - size.w
        val y0 = box.bottom - m - size.h

        fun free(
            x: Double,
            y: Double,
        ): Boolean {
            val r = SvgRect(x, y, size.w, size.h)
            return gapToForeign(r, f, others) >= CLEARANCE && laneOk(r, f.lanes)
        }
        if (free(x0, y0)) return true
        var k = 1
        while (true) {
            val ax = x0 - STEP * k
            val by = y0 - STEP * k
            val aOk = ax >= box.x + box.w / 2 - size.w / 2
            val bOk = by >= box.y + box.h / 2 - size.h / 2
            if (!aOk && !bOk) return false
            if (aOk && free(ax, y0)) return true
            if (bOk && free(x0, by)) return true
            k++
        }
    }

    fun report(
        svg: String,
        svgWithoutNote: String?,
    ): LooseCornerReport {
        val free = freeNotes(svg)
        val f = foreign(svg)
        val box = contentBox(f)
        var l1 = false
        var l2 = 0
        var l2b = 0
        // The free-standing notes form one block (all or nothing): the corner search is done for the block rectangle.
        if (free.isNotEmpty() && box != null) {
            val rs = free.values
            val block =
                SvgRect(
                    rs.minOf { it.x },
                    rs.minOf { it.y },
                    rs.maxOf { it.right } - rs.minOf { it.x },
                    rs.maxOf { it.bottom } - rs.minOf { it.y },
                )
            if ((block.bottom > box.bottom + OUTSIDE_TOL || block.right > box.right + OUTSIDE_TOL) &&
                cornerFree(block, box, f, emptyList())
            ) {
                l1 = true
            }
        }
        for ((id, r) in free) {
            val others = free.filterKeys { it != id }.values.toList()
            val shrunk = SvgRect(r.x + 1, r.y + 1, r.w - 2, r.h - 2)
            var hit = 0
            hit += f.nodes.count { it.overlaps(shrunk) }
            hit += f.labels.count { it.overlaps(shrunk) }
            hit += f.titles.count { it.overlaps(shrunk) }
            hit += f.edges.count { NoteLeaderMetrics.crossesRect(it, r) }
            hit += f.lanes.count { lane -> laneLineCrosses(lane, r) }
            l2 += hit
            l2b += (f.nodes + f.labels + f.titles + others).count { r.gapTo(it) < CLEARANCE - EPS } +
                f.edges.count { SvgGeometry.distance(it, r) < CLEARANCE - EPS } +
                f.lanes.count { lane -> laneGapBelow(lane, r) }
        }
        return LooseCornerReport(imageArea(svg), svgWithoutNote?.let { imageArea(it) }, l1, l2, l2b, free.keys.toList())
    }

    /** The lane border line passes through the interior of the note. */
    private fun laneLineCrosses(
        lane: SvgRect,
        r: SvgRect,
    ): Boolean {
        val inside = r.x >= lane.x && r.right <= lane.right && r.y >= lane.y && r.bottom <= lane.bottom
        val outside = !r.overlaps(lane)
        return !inside && !outside
    }

    /** Inside the lane: gap to its border below the inset; outside: gap to the lane below the clearance. */
    private fun laneGapBelow(
        lane: SvgRect,
        r: SvgRect,
    ): Boolean {
        val inside = r.x >= lane.x && r.right <= lane.right && r.y >= lane.y && r.bottom <= lane.bottom
        if (inside) {
            return min(min(r.x - lane.x, lane.right - r.right), min(r.y - lane.y, lane.bottom - r.bottom)) < LANE_INSET - EPS
        }
        return !r.overlaps(lane) && r.gapTo(lane) < CLEARANCE - EPS
    }
}
