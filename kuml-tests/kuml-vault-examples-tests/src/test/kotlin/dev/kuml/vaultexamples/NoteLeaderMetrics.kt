@file:Suppress("RequireNamedArguments")

package dev.kuml.vaultexamples

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One note anchor line (leader) read from the SVG. [pts] runs from the note end to the anchor end. */
internal data class Leader(
    val id: String,
    val noteId: String,
    val targetId: String,
    val pts: List<Pair<Double, Double>>,
    val length: Double,
)

/** Violation counts of one leader (or the sum over a diagram) plus the polyline length (not a violation). */
internal data class LeaderViolations(
    val k1: Int,
    val k2: Int,
    val k3: Int,
    val k4: Int,
    val k5: Int,
    val length: Double,
) {
    val sum: Int get() = k1 + k2 + k3 + k4 + k5

    fun lexVector(): List<Int> = listOf(k1, k2, k3, k4, k5)

    override fun toString(): String = "K1=$k1 K2=$k2 K3=$k3 K4=$k4 K5=$k5 L=${String.format(java.util.Locale.ROOT, "%.3f", length)}"
}

internal class LeaderReport(
    val perLeader: Map<String, LeaderViolations>,
    val total: LeaderViolations,
)

/**
 * PAL Welle X5 detector for note anchor lines (leaders), measured on the rendered SVG with rectangles and polylines only
 * (never glyph metrics, so the result is the same on every machine).
 *
 * - K1 a segment of the leader crosses the interior (shrunk by 1 px) of a foreign node or a foreign note. Exempt: the target
 *   node, every node that contains the anchor point (owner frame, composite ancestors) and every node that contains the note.
 * - K2 the leader crosses the border of a swimlane that does not contain the anchor point.
 * - K3 the leader crosses the label of a foreign edge, or touches the label of its own connection (end point closer than
 *   [OWN_LABEL_MIN_DIST] px to the label rectangle, or rectangle crossed).
 * - K4 the leader properly crosses a foreign edge polyline (collinear contact and contacts at segment ends are not counted).
 * - K5 two leaders of different notes share an anchor: end points closer than [CO_ANCHOR_DIST] px or a collinear overlap
 *   longer than [CO_ANCHOR_OVERLAP] px.
 * - L the polyline length.
 */
internal object NoteLeaderMetrics {
    const val OWN_LABEL_MIN_DIST = 8.0
    const val CO_ANCHOR_DIST = 4.0
    const val CO_ANCHOR_OVERLAP = 4.0
    private const val SHRINK = 1.0
    private const val OWNER_MARGIN = 1.5

    private val linkRe =
        Regex(
            """<g id="(note-link:([^":]+)::([^"]+))">\s*(?:<line x1="([-0-9.]+)" y1="([-0-9.]+)" x2="([-0-9.]+)" y2="([-0-9.]+)"|<path d="([^"]*)")[^>]*kuml-note-anchor[^>]*/>(?:\s*<circle cx="([-0-9.]+)" cy="([-0-9.]+)")?""",
        )

    private fun dist(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
    ) = hypot(a.first - b.first, a.second - b.second)

    private fun length(pts: List<Pair<Double, Double>>) = pts.zipWithNext().sumOf { (p, q) -> dist(p, q) }

    fun leaders(svg: String): List<Leader> {
        val notes = noteRects(svg)
        return linkRe
            .findAll(svg)
            .map { m ->
                val v = m.groupValues
                var pts =
                    if (v[8].isNotEmpty()) {
                        SvgGeometry.pathPoints(v[8])
                    } else {
                        listOf(v[4].toDouble() to v[5].toDouble(), v[6].toDouble() to v[7].toDouble())
                    }
                // Orient: first point at the note, last point at the anchor.
                val note = notes[v[2]]
                if (note != null && pts.size >= 2) {
                    val a = rectDist(pts.first(), note)
                    val b = rectDist(pts.last(), note)
                    if (b < a) pts = pts.reversed()
                }
                Leader(v[1], v[2], v[3], pts, length(pts))
            }.filter { it.pts.size >= 2 }
            .toList()
    }

    private fun rectDist(
        p: Pair<Double, Double>,
        r: SvgRect,
    ): Double {
        val dx = max(0.0, max(r.x - p.first, p.first - r.right))
        val dy = max(0.0, max(r.y - p.second, p.second - r.bottom))
        return hypot(dx, dy)
    }

    fun noteRects(svg: String): Map<String, SvgRect> =
        NoteHintClearance.noteHead
            .findAll(svg)
            .associate { m ->
                val v = m.groupValues
                v[1] to SvgRect(v[2].toDouble(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble())
            }

    private fun isLane(id: String) = id.startsWith("activityPartition")

    /** Node rectangles without notes and without swimlanes. */
    fun nodeRects(svg: String): Map<String, SvgRect> {
        val notes = noteRects(svg).keys
        val out = LinkedHashMap<String, SvgRect>()
        for (n in SvgGeometry.nodes(svg)) {
            if (n.id in notes || isLane(n.id) || n.id.startsWith("note-link")) continue
            val r = NoteHintClearance.nodeBox(n) ?: shapeBox(n) ?: continue
            out[n.id] = r
        }
        return out
    }

    private fun shapeBox(n: SvgNode): SvgRect? {
        fun a(
            tag: String,
            name: String,
        ) = Regex("""\b$name="([-0-9.]+)"""")
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
        Regex("""<ellipse\b[^>]*>""").find(n.body)?.value?.let { t ->
            val cx = a(t, "cx") ?: 0.0
            val cy = a(t, "cy") ?: 0.0
            val rx = a(t, "rx") ?: return null
            val ry = a(t, "ry") ?: return null
            return SvgRect(n.tx + cx - rx, n.ty + cy - ry, 2 * rx, 2 * ry)
        }
        Regex("""<circle\b[^>]*>""").find(n.body)?.value?.let { t ->
            val cx = a(t, "cx") ?: 0.0
            val cy = a(t, "cy") ?: 0.0
            val r = a(t, "r") ?: return null
            return SvgRect(n.tx + cx - r, n.ty + cy - r, 2 * r, 2 * r)
        }
        return null
    }

    /** Swimlane rectangles (`activityPartition:` groups with a `kuml-lane` rect). */
    fun laneBoundaries(svg: String): List<SvgRect> =
        SvgGeometry
            .nodes(svg)
            .filter { isLane(it.id) && !it.id.startsWith("activityPartitionHeader") }
            .mapNotNull { n ->
                val tag = Regex("""<rect\b[^>]*kuml-lane"[^>]*>""").find(n.body)?.value ?: return@mapNotNull null

                fun a(name: String) =
                    Regex("""\b$name="([-0-9.]+)"""")
                        .find(tag)
                        ?.groupValues
                        ?.get(1)
                        ?.toDouble() ?: 0.0
                SvgRect(n.tx + a("x"), n.ty + a("y"), a("width"), a("height"))
            }

    private val edgeStartRe =
        Regex("""<g id="([^"]+)">\s*(?=<(?:line|path|polyline)[^>]*class="kuml-edge")""")
    private val anyLabelRe =
        Regex("""<g class="kuml-edge-label"([^>]*?) data-label-rect="([-0-9.]+) ([-0-9.]+) ([-0-9.]+) ([-0-9.]+)"""")

    /**
     * Edge label rectangles with the id of their edge (absolute coordinates): `data-edge-id` / `data-label-id` when present,
     * else the id of the edge group the label sits in.
     */
    fun labelRects(svg: String): List<Pair<String, SvgRect>> {
        val starts = edgeStartRe.findAll(svg).map { it.range.first to it.groupValues[1] }.toList()
        return anyLabelRe
            .findAll(svg)
            .map { m ->
                val attrs = m.groupValues[1]
                val own =
                    Regex("""data-edge-id="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                        ?: Regex("""data-label-id="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                        ?: starts.lastOrNull { it.first < m.range.first }?.second
                        ?: ""
                own to
                    SvgRect(
                        m.groupValues[2].toDouble(),
                        m.groupValues[3].toDouble(),
                        m.groupValues[4].toDouble(),
                        m.groupValues[5].toDouble(),
                    )
            }.toList()
    }

    /** True when the edge or label id [id] belongs to the leader target [target] (`conn:` prefix of connections ignored). */
    fun sameEdge(
        id: String,
        target: String,
    ): Boolean = id == target || id.removePrefix("conn:") == target

    // ---- geometry -------------------------------------------------------------------------------------------------

    fun segIntersect(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        c: Pair<Double, Double>,
        d: Pair<Double, Double>,
    ): Pair<Double, Double>? {
        val r = b.first - a.first to b.second - a.second
        val s = d.first - c.first to d.second - c.second
        val den = r.first * s.second - r.second * s.first
        if (abs(den) < 1e-9) return null
        val t = ((c.first - a.first) * s.second - (c.second - a.second) * s.first) / den
        val u = ((c.first - a.first) * r.second - (c.second - a.second) * r.first) / den
        if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null
        return a.first + t * r.first to a.second + t * r.second
    }

    private fun near(
        p: Pair<Double, Double>,
        vararg q: Pair<Double, Double>,
    ) = q.any { dist(p, it) < 0.5 }

    private fun inside(
        p: Pair<Double, Double>,
        r: SvgRect,
        margin: Double = 0.0,
    ) = p.first > r.x - margin && p.first < r.right + margin && p.second > r.y - margin && p.second < r.bottom + margin

    /** True when the polyline enters the interior of [r] shrunk by [shrink] px. */
    fun crossesRect(
        pts: List<Pair<Double, Double>>,
        r: SvgRect,
        shrink: Double = SHRINK,
    ): Boolean {
        val s = SvgRect(r.x + shrink, r.y + shrink, r.w - 2 * shrink, r.h - 2 * shrink)
        if (s.w <= 0.0 || s.h <= 0.0) return false
        for (i in 0 until pts.size - 1) if (segmentEntersRect(pts[i], pts[i + 1], s)) return true
        return false
    }

    /** Liang-Barsky: the open segment has a part strictly inside [r]. */
    private fun segmentEntersRect(
        a: Pair<Double, Double>,
        b: Pair<Double, Double>,
        r: SvgRect,
    ): Boolean {
        val dx = b.first - a.first
        val dy = b.second - a.second
        var t0 = 0.0
        var t1 = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(a.first - r.x, r.right - a.first, a.second - r.y, r.bottom - a.second)
        for (i in 0..3) {
            if (abs(p[i]) < 1e-12) {
                if (q[i] < 0.0) return false
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0.0) t0 = max(t0, t) else t1 = min(t1, t)
            }
        }
        return t1 - t0 > 1e-9
    }

    /** True when the polyline crosses one of the four sides of [r]. */
    private fun crossesBorder(
        pts: List<Pair<Double, Double>>,
        r: SvgRect,
    ): Boolean {
        val c =
            listOf(r.x to r.y, r.right to r.y, r.right to r.bottom, r.x to r.bottom)
        for (i in 0 until pts.size - 1) {
            for (k in 0..3) {
                if (segIntersect(pts[i], pts[i + 1], c[k], c[(k + 1) % 4]) != null) {
                    // A leader that merely ends on the border (anchor on the frame) is not a crossing.
                    return true
                }
            }
        }
        return false
    }

    private fun foreignEdgeCrossings(
        pts: List<Pair<Double, Double>>,
        edges: Map<String, List<Pair<Double, Double>>>,
        target: String,
    ): Int {
        var n = 0
        for ((id, e) in edges) {
            if (sameEdge(id, target)) continue
            for (i in 0 until e.size - 1) {
                for (k in 0 until pts.size - 1) {
                    val x = segIntersect(pts[k], pts[k + 1], e[i], e[i + 1]) ?: continue
                    if (near(x, pts[k], pts[k + 1], e[i], e[i + 1])) continue
                    n++
                }
            }
        }
        return n
    }

    private fun collinearOverlap(
        a: List<Pair<Double, Double>>,
        b: List<Pair<Double, Double>>,
    ): Double {
        var best = 0.0
        for (i in 0 until a.size - 1) {
            for (k in 0 until b.size - 1) {
                val p = a[i]
                val q = a[i + 1]
                val c = b[k]
                val d = b[k + 1]
                val len = dist(p, q)
                if (len < 1e-9 || dist(c, d) < 1e-9) continue
                val ux = (q.first - p.first) / len
                val uy = (q.second - p.second) / len

                fun off(z: Pair<Double, Double>) = abs((z.first - p.first) * uy - (z.second - p.second) * ux)
                if (off(c) > 0.5 || off(d) > 0.5) continue
                val t1 = (c.first - p.first) * ux + (c.second - p.second) * uy
                val t2 = (d.first - p.first) * ux + (d.second - p.second) * uy
                val ov = min(len, max(t1, t2)) - max(0.0, min(t1, t2))
                best = max(best, ov)
            }
        }
        return best
    }

    fun report(svg: String): LeaderReport {
        val leaders = leaders(svg)
        val notes = noteRects(svg)
        val nodes = nodeRects(svg)
        val lanes = laneBoundaries(svg)
        val labels = labelRects(svg)
        val edges = SvgGeometry.edgePolylines(svg)
        val per = LinkedHashMap<String, LeaderViolations>()
        val k5 = HashMap<String, Int>()
        var k5Total = 0
        for (i in leaders.indices) {
            for (j in i + 1 until leaders.size) {
                val a = leaders[i]
                val b = leaders[j]
                if (a.noteId == b.noteId) continue
                val share =
                    dist(a.pts.last(), b.pts.last()) < CO_ANCHOR_DIST || collinearOverlap(a.pts, b.pts) > CO_ANCHOR_OVERLAP
                if (share) {
                    k5[a.id] = (k5[a.id] ?: 0) + 1
                    k5[b.id] = (k5[b.id] ?: 0) + 1
                    k5Total++
                }
            }
        }
        for (l in leaders) {
            val anchor = l.pts.last()
            val noteRect = notes[l.noteId]
            val noteCenter = noteRect?.let { it.cx to it.cy }
            var k1 = 0
            for ((id, r) in nodes) {
                if (id == l.targetId) continue
                if (inside(anchor, r, OWNER_MARGIN)) continue
                if (noteCenter != null && inside(noteCenter, r)) continue
                if (crossesRect(l.pts, r)) k1++
            }
            for ((id, r) in notes) {
                if (id == l.noteId) continue
                if (crossesRect(l.pts, r)) k1++
            }
            var k2 = 0
            for (r in lanes) {
                if (inside(anchor, r, OWNER_MARGIN)) continue
                if (crossesBorder(l.pts, r)) k2++
            }
            var k3 = 0
            for ((owner, r) in labels) {
                if (sameEdge(owner, l.targetId)) {
                    if (rectDist(anchor, r) < OWN_LABEL_MIN_DIST || crossesRect(l.pts, r, 0.0)) k3++
                } else if (crossesRect(l.pts, r, 0.0)) {
                    k3++
                }
            }
            val k4 = foreignEdgeCrossings(l.pts, edges, l.targetId)
            per[l.id] = LeaderViolations(k1, k2, k3, k4, k5[l.id] ?: 0, l.length)
        }
        val total =
            LeaderViolations(
                k1 = per.values.sumOf { it.k1 },
                k2 = per.values.sumOf { it.k2 },
                k3 = per.values.sumOf { it.k3 },
                k4 = per.values.sumOf { it.k4 },
                k5 = k5Total,
                length = per.values.sumOf { it.length },
            )
        return LeaderReport(per, total)
    }
}
