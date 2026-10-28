package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.sysml2.edge.EdgeLabelPart
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Horizontal alignment of the text inside a placed label box. */
internal enum class StmLabelAnchor { Left, Right, Center }

/** A placed, wrapped STM edge label: [lines] of styled parts centred on ([cx], [cy]) inside a [w] x [h] box. */
internal data class StmLabelBlock(
    val lines: List<List<EdgeLabelPart>>,
    val fullText: String,
    val truncated: Boolean,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val anchor: StmLabelAnchor = StmLabelAnchor.Center,
)

/** Kind of a node-like obstacle for [StmLabelPlacer.place]. */
internal enum class StmObstacleKind { Simple, Composite, Region }

/**
 * A node-like obstacle in final canvas coordinates. [Simple][StmObstacleKind.Simple] states and
 * pseudo-states block their whole rectangle. A [Composite][StmObstacleKind.Composite] blocks only
 * its frame band, header band ([headerBottom]), [dividers] and [regionNameRects] for edges whose
 * anchor segment runs inside it, and its whole rectangle for every other edge. A
 * [Region][StmObstacleKind.Region] is not drawn on its own and blocks nothing (its name and
 * separators arrive via the composite).
 */
internal data class StmObstacleNode(
    val id: String,
    val kind: StmObstacleKind,
    val rect: Rect,
    val headerBottom: Float? = null,
    val regionNameRects: List<Rect> = emptyList(),
    val dividers: List<Pair<Point, Point>> = emptyList(),
)

/**
 * Deterministic placement of SysML 2 state-transition labels. Text is wrapped (trigger, guard and
 * effect on one line up to 180 px, otherwise one part per line, at most three lines, long parts
 * truncated with an ellipsis). Candidates sit beside a long, unshared segment of the edge (the
 * polyline of the actually drawn curve) at several fractions, distance steps and both sides; the
 * first candidate that touches no edge stripe, state, frame band or placed label wins, otherwise
 * the cheapest one (edges cost most). Edges are processed in id order, so output never depends on
 * map iteration. Geometry uses [CHAR_W], never measured text widths, so it is font independent.
 */
internal object StmLabelPlacer {
    private const val CHAR_W = 7.2f
    private const val MAX_LINE_PX = 180f
    private const val LINE_H = 12f
    private const val PAD = 3f
    private const val GAP = 6f
    private const val MAX_PART_CHARS = 28
    private const val COLLISION_CHECK_LIMIT = 500
    private const val STRIPE = 4f
    private const val ARROW = 10f
    private const val MIN_ANCHOR_LEN = 16f
    private const val BEZIER_SAMPLES = 16
    private const val MAX_ANCHOR_SEGMENTS = 6
    private const val WORK_BUDGET = 3_000_000L
    private val STEPS = listOf(0f, 8f, 16f)
    private val FRACTIONS = listOf(0.5f, 0.35f, 0.65f, 0.2f, 0.8f)

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

    /**
     * Places the labels of all [edges] whose parts are non-empty. Edges without parts take part as
     * obstacles only. [frame] gives the top-left limit: labels never cross its top; they may cross its
     * left edge only if every in-frame spot collides (the caller then shifts the diagram right), and
     * may extend beyond it to the right and bottom, where the caller grows the canvas.
     */
    fun place(
        edges: List<Triple<String, EdgeRoute, List<EdgeLabelPart>>>,
        nodes: List<StmObstacleNode>,
        frame: Rect,
    ): Map<String, StmLabelBlock> {
        val out = LinkedHashMap<String, StmLabelBlock>()
        val sorted = edges.sortedBy { it.first }
        val polys = sorted.associate { (id, route, _) -> id to polyline(route) }
        val segs = ArrayList<Seg>()
        for ((id, _, _) in sorted) {
            val pl = polys.getValue(id)
            for (i in 0 until pl.size - 1) segs += Seg(edge = id, a = pl[i], b = pl[i + 1], index = i)
        }
        val arrows = ArrayList<Box>()
        for ((id, _, _) in sorted) {
            val pl = polys.getValue(id)
            if (pl.size < 2) continue
            for (p in listOf(pl.first(), pl.last())) {
                arrows += Box(x0 = p.x - ARROW / 2f, y0 = p.y - ARROW / 2f, x1 = p.x + ARROW / 2f, y1 = p.y + ARROW / 2f)
            }
        }
        val placed = ArrayList<Box>()
        val ctx = Ctx(segs = segs, arrows = arrows, nodes = nodes, placed = placed, frame = frame)
        val check = sorted.size <= COLLISION_CHECK_LIMIT
        var work = 0L
        for ((id, _, parts) in sorted) {
            val (lines, cut) = wrap(parts)
            if (lines.isEmpty()) continue
            val chars = lines.maxOf { l -> l.sumOf { it.text.length } + (l.size - 1) }
            val w = chars * CHAR_W + 2 * PAD
            val h = lines.size * LINE_H + 2 * PAD
            val pl = polys.getValue(id)
            val cands = candidates(edge = id, pl = pl, allSegs = segs, w = w, h = h)
            var best: Cand? = cands.firstOrNull()
            if (check && cands.isNotEmpty()) {
                var bestCost = Float.MAX_VALUE
                for (c in cands) {
                    if (work > WORK_BUDGET) break
                    if (c.box.y0 < frame.origin.y) continue
                    // Left overflow is allowed (the caller shifts the diagram right) but ranks after any clean in-frame spot.
                    var cost = ctx.cost(edge = id, c = c)
                    if (c.box.x0 < frame.origin.x) cost += 1f + (frame.origin.x - c.box.x0) * 0.01f
                    work += ctx.lastWork
                    if (cost < bestCost) {
                        bestCost = cost
                        best = c
                        if (cost == 0f) break
                    }
                }
            }
            val chosen =
                best ?: Cand(box = fallbackBox(pl = pl, w = w, h = h), mid = pl.firstOrNull() ?: Point(x = 0f, y = 0f), vertical = false)
            placed += chosen.box
            val anchor =
                when {
                    !chosen.vertical -> StmLabelAnchor.Center
                    chosen.rightOrLeft >= 0f -> StmLabelAnchor.Left
                    else -> StmLabelAnchor.Right
                }
            out[id] =
                StmLabelBlock(
                    lines = lines,
                    fullText = parts.joinToString(" ") { it.text },
                    truncated = cut,
                    cx = (chosen.box.x0 + chosen.box.x1) / 2f,
                    cy = (chosen.box.y0 + chosen.box.y1) / 2f,
                    w = w,
                    h = h,
                    anchor = anchor,
                )
        }
        return out
    }

    private data class Box(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) {
        fun inflate(d: Float) = Box(x0 = x0 - d, y0 = y0 - d, x1 = x1 + d, y1 = y1 + d)

        fun overlaps(o: Box) = min(x1, o.x1) - max(x0, o.x0) > 0f && min(y1, o.y1) - max(y0, o.y0) > 0f

        fun contains(o: Box) = o.x0 >= x0 && o.y0 >= y0 && o.x1 <= x1 && o.y1 <= y1

        fun area(o: Box): Float {
            val dx = min(x1, o.x1) - max(x0, o.x0)
            val dy = min(y1, o.y1) - max(y0, o.y0)
            return if (dx > 0f && dy > 0f) dx * dy else 0f
        }
    }

    private data class Seg(
        val edge: String,
        val a: Point,
        val b: Point,
        val index: Int,
    ) {
        val length: Float get() = dist(a = a, b = b)
    }

    private class Cand(
        val box: Box,
        val mid: Point,
        val vertical: Boolean,
        val rightOrLeft: Float = 0f,
    )

    private class Ctx(
        val segs: List<Seg>,
        val arrows: List<Box>,
        val nodes: List<StmObstacleNode>,
        val placed: List<Box>,
        val frame: Rect,
    ) {
        var lastWork = 0L

        /** Weighted collision cost of [c]; 0 means collision free. */
        fun cost(
            edge: String,
            c: Cand,
        ): Float {
            var cost = 0f
            lastWork = (segs.size + arrows.size + nodes.size + placed.size).toLong()
            val b = c.box
            val grown = b.inflate(STRIPE)
            val near = b.inflate(GAP * 2f)
            for (s in segs) {
                if (segHitsBox(a = s.a, b = s.b, box = grown)) {
                    cost += 1000f
                } else if (s.edge != edge && segHitsBox(a = s.a, b = s.b, box = near)) {
                    // Soft: keep the label visibly closer to its own edge than to a foreign one.
                    cost += 0.5f
                }
            }
            for (a in arrows) if (a.overlaps(b)) cost += 1000f
            for (n in nodes) cost += nodeCost(n = n, b = b, mid = c.mid)
            for (p in placed) if (p.inflate(STRIPE).overlaps(b)) cost += 300f + p.area(b)
            return cost
        }

        private fun nodeCost(
            n: StmObstacleNode,
            b: Box,
            mid: Point,
        ): Float {
            val r =
                Box(
                    x0 = n.rect.origin.x,
                    y0 = n.rect.origin.y,
                    x1 = n.rect.origin.x + n.rect.size.width,
                    y1 = n.rect.origin.y + n.rect.size.height,
                )
            return when (n.kind) {
                StmObstacleKind.Region -> 0f
                StmObstacleKind.Simple -> if (r.inflate(STRIPE).overlaps(b)) 500f + r.area(b) else 0f
                StmObstacleKind.Composite ->
                    if (mid.x in r.x0..r.x1 && mid.y in r.y0..r.y1) {
                        var c = 0f
                        if (r.inflate(STRIPE).overlaps(b) && !r.inflate(-STRIPE).contains(b)) c += 500f
                        n.headerBottom?.let { hb ->
                            if (Box(x0 = r.x0, y0 = r.y0, x1 = r.x1, y1 = hb + STRIPE).overlaps(b)) c += 500f
                        }
                        for ((p, q) in n.dividers) if (segHitsBox(a = p, b = q, box = b.inflate(STRIPE))) c += 500f
                        for (nr in n.regionNameRects) {
                            val nb =
                                Box(
                                    x0 = nr.origin.x,
                                    y0 = nr.origin.y,
                                    x1 = nr.origin.x + nr.size.width,
                                    y1 =
                                        nr.origin.y + nr.size.height,
                                )
                            if (nb.overlaps(b)) c += 500f
                        }
                        c
                    } else if (r.inflate(STRIPE).overlaps(b)) {
                        500f + r.area(b)
                    } else {
                        0f
                    }
            }
        }
    }

    private fun fallbackBox(
        pl: List<Point>,
        w: Float,
        h: Float,
    ): Box {
        val p = pl.firstOrNull() ?: Point(x = 0f, y = 0f)
        return Box(x0 = p.x + GAP, y0 = p.y + GAP, x1 = p.x + GAP + w, y1 = p.y + GAP + h)
    }

    /** Polyline of the drawn curve; Bezier curves are sampled. Non-finite points are dropped. */
    private fun polyline(route: EdgeRoute): List<Point> {
        val raw =
            when (route) {
                is EdgeRoute.Direct -> listOf(route.source, route.target)
                is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
                is EdgeRoute.Bezier -> sampleBezier(route)
            }
        return raw.filter { it.x.isFinite() && it.y.isFinite() }
    }

    private fun sampleBezier(r: EdgeRoute.Bezier): List<Point> {
        val cp = r.controlPoints
        if (cp.isEmpty()) return listOf(r.source, r.target)
        val ctrl = if (cp.size >= 2) listOf(r.source, cp[0], cp[1], r.target) else listOf(r.source, cp[0], r.target)
        return (0..BEZIER_SAMPLES).map { i -> deCasteljau(pts = ctrl, t = i.toFloat() / BEZIER_SAMPLES) }
    }

    private fun deCasteljau(
        pts: List<Point>,
        t: Float,
    ): Point {
        var cur = pts
        while (cur.size > 1) {
            cur = cur.zipWithNext { a, b -> Point(x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t) }
        }
        return cur.first()
    }

    /** Collinear (within 1 px) and overlapping by more than 1 px with a segment of another edge. */
    private fun shared(
        s: Seg,
        all: List<Seg>,
    ): Boolean {
        val len = s.length
        if (len < 0.001f) return false
        val dx = (s.b.x - s.a.x) / len
        val dy = (s.b.y - s.a.y) / len
        for (o in all) {
            if (o.edge == s.edge) continue

            fun along(p: Point) = (p.x - s.a.x) * dx + (p.y - s.a.y) * dy

            fun off(p: Point) = abs((p.x - s.a.x) * -dy + (p.y - s.a.y) * dx)
            if (off(o.a) > 1f || off(o.b) > 1f) continue
            val lo = max(0f, min(along(o.a), along(o.b)))
            val hi = min(len, max(along(o.a), along(o.b)))
            if (hi - lo > 1f) return true
        }
        return false
    }

    private fun candidates(
        edge: String,
        pl: List<Point>,
        allSegs: List<Seg>,
        w: Float,
        h: Float,
    ): List<Cand> {
        val own = (0 until pl.size - 1).map { Seg(edge = edge, a = pl[it], b = pl[it + 1], index = it) }
        if (own.isEmpty()) return emptyList()
        val long = own.filter { it.length >= MIN_ANCHOR_LEN }
        val unshared = long.filter { !shared(s = it, all = allSegs) }
        val pool = unshared.ifEmpty { long.ifEmpty { own } }
        val anchors = pool.sortedWith(compareBy({ -it.length.roundToInt() }, { it.index })).take(MAX_ANCHOR_SEGMENTS)
        val out = ArrayList<Cand>()
        for (s in anchors) {
            val len = max(s.length, 0.001f)
            val dx = s.b.x - s.a.x
            val dy = s.b.y - s.a.y
            val nx = -dy / len
            val ny = dx / len
            val vertical = abs(dy) >= abs(dx)
            // Vertical segments: right before left; horizontal: above before below.
            val sides =
                if (vertical) {
                    (
                        if (nx >
                            0f
                        ) {
                            listOf(1f, -1f)
                        } else {
                            listOf(-1f, 1f)
                        }
                    )
                } else {
                    (if (ny < 0f) listOf(1f, -1f) else listOf(-1f, 1f))
                }
            val ext = abs(nx) * w / 2f + abs(ny) * h / 2f
            for (f in FRACTIONS) {
                val px = s.a.x + dx * f
                val py = s.a.y + dy * f
                for (step in STEPS) {
                    for (side in sides) {
                        val cx = px + nx * side * (GAP + step + ext)
                        val cy = py + ny * side * (GAP + step + ext)
                        out +=
                            Cand(
                                box = Box(x0 = cx - w / 2f, y0 = cy - h / 2f, x1 = cx + w / 2f, y1 = cy + h / 2f),
                                mid = Point(x = (s.a.x + s.b.x) / 2f, y = (s.a.y + s.b.y) / 2f),
                                vertical = vertical,
                                rightOrLeft = cx - px,
                            )
                    }
                }
            }
        }
        return out
    }

    private fun segHitsBox(
        a: Point,
        b: Point,
        box: Box,
    ): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        var t0 = 0f
        var t1 = 1f
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(a.x - box.x0, box.x1 - a.x, a.y - box.y0, box.y1 - a.y)
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return false
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0f) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
            }
        }
        return true
    }

    private fun dist(
        a: Point,
        b: Point,
    ): Float = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
}
