package dev.kuml.io.svg.c4

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.label.LabelCostModel
import dev.kuml.layout.bridge.label.LabelPlacer
import dev.kuml.layout.bridge.label.LabelRequest
import dev.kuml.layout.bridge.label.LabelRole
import dev.kuml.layout.bridge.label.PlacedLabel
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One relationship edge of a C4 diagram as the label plan sees it. */
internal data class C4LabelEdge(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val text: String,
)

/**
 * Result of [C4EdgeLabelPlan.plan].
 *
 * @property routes drawn routes by edge id (opposite and parallel edges pushed apart)
 * @property placed planned label rectangle by edge id
 * @property lines wrapped label lines by edge id
 */
internal data class C4LabelPlan(
    val routes: Map<String, EdgeRoute>,
    val placed: Map<String, PlacedLabel>,
    val lines: Map<String, List<String>>,
    val lineHeightPx: Float,
    val fontSizePx: Float,
)

/**
 * Plans the relationship labels of C4 diagrams with the shared [LabelPlacer] (PAL wave 2G-b). A label is wrapped at
 * [WRAP_CHARS] characters on word boundaries and is never shortened. Edges that connect the same two elements are
 * pushed [PARALLEL_GAP_PX] apart first; the label of such a pair is kept off the corridor between its two lines.
 * Everything is in drawn coordinates (canvas padding applied); the plan is built once per diagram, in the order
 * nodes, routes, plan, drawing. Deterministic: every iteration runs in sorted order.
 */
internal object C4EdgeLabelPlan {
    const val WRAP_CHARS: Int = 36
    const val LINE_H_FACTOR: Float = 1.2f
    const val PARALLEL_GAP_PX: Float = 24f

    /** DoS guard: a longer label text is cut here (with an ellipsis) before wrapping. */
    const val MAX_LABEL_CHARS: Int = 4096

    private const val CHAR_FACTOR = 0.52f
    private const val PAD_PX = 3f

    /** A label may sit up to [MAX_LABEL_DISTANCE_PX] from its own line; wide labels in narrow corridors need the room. */
    const val MAX_LABEL_DISTANCE_PX: Float = 24f
    private val COSTS = LabelCostModel(edgeOffsets = listOf(4f, 8f, 12f, 18f, 24f), maxDistancePx = MAX_LABEL_DISTANCE_PX)

    /** Wraps [text] at word boundaries; a word longer than [WRAP_CHARS] is split hard. No line limit, no ellipsis. */
    fun wrap(text: String): List<String> {
        val capped = if (text.length > MAX_LABEL_CHARS) text.take(MAX_LABEL_CHARS) + "…" else text
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (rawWord in capped.split(' ', '\n', '\t').filter { it.isNotEmpty() }) {
            var word = rawWord
            while (word.length > WRAP_CHARS) {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur = StringBuilder()
                }
                out += word.take(WRAP_CHARS)
                word = word.drop(WRAP_CHARS)
            }
            when {
                cur.isEmpty() -> cur.append(word)
                cur.length + 1 + word.length <= WRAP_CHARS -> cur.append(' ').append(word)
                else -> {
                    out += cur.toString()
                    cur = StringBuilder(word)
                }
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Size of the label rectangle for [lines] at [fontSizePx]. */
    fun sizeOf(
        lines: List<String>,
        fontSizePx: Float,
    ): Size =
        Size(
            width = (lines.maxOfOrNull { it.length } ?: 0) * fontSizePx * CHAR_FACTOR + 2 * PAD_PX,
            height = lines.size * fontSizePx * LINE_H_FACTOR + 2 * PAD_PX,
        )

    fun plan(
        routes: Map<String, EdgeRoute>,
        nodes: Map<String, Rect>,
        edges: List<C4LabelEdge>,
        fontSizePx: Float,
    ): C4LabelPlan {
        val lineH = fontSizePx * LINE_H_FACTOR
        val sorted = edges.sortedWith(compareBy({ it.sourceId }, { it.targetId }, { it.text }, { it.id }))
        val avoid = HashMap<String, MutableList<Rect>>()
        val separated = separateParallel(routes = routes, nodes = nodes, edges = sorted, avoid = avoid)
        val lines = LinkedHashMap<String, List<String>>()
        for (e in sorted) if (e.text.isNotBlank()) lines[e.id] = wrap(e.text)
        if (lines.isEmpty()) {
            return C4LabelPlan(
                routes = separated,
                placed = emptyMap(),
                lines = emptyMap(),
                lineHeightPx = lineH,
                fontSizePx = fontSizePx,
            )
        }

        val index = ObstacleIndex()
        for ((id, r) in nodes.entries.sortedBy { it.key }) index.addRect(kind = ObstacleKind.NODE, r = r, ownerId = id)
        val polylines = separated.entries.sortedBy { it.key }.associate { it.key to polylineOf(it.value) }
        for ((id, pts) in polylines) index.addPolyline(kind = ObstacleKind.EDGE, pts = pts, ownerId = id)
        val requests = ArrayList<LabelRequest>()
        for (e in sorted) {
            val l = lines[e.id] ?: continue
            val pts = polylines[e.id] ?: continue
            if (pts.size < 2) continue
            requests +=
                LabelRequest(
                    id = e.id,
                    role = LabelRole.EDGE_CENTER,
                    ownerId = e.id,
                    size = sizeOf(lines = l, fontSizePx = fontSizePx),
                    polyline = pts,
                    avoid = avoid[e.id].orEmpty(),
                )
        }
        val placed = if (requests.isEmpty()) emptyMap() else LabelPlacer.place(requests = requests, index = index, costs = COSTS)
        return C4LabelPlan(routes = separated, placed = placed, lines = lines, lineHeightPx = lineH, fontSizePx = fontSizePx)
    }

    // ── Opposite and parallel edges ──────────────────────────────────────────────────────────────

    private fun separateParallel(
        routes: Map<String, EdgeRoute>,
        nodes: Map<String, Rect>,
        edges: List<C4LabelEdge>,
        avoid: MutableMap<String, MutableList<Rect>>,
    ): Map<String, EdgeRoute> {
        val out = LinkedHashMap(routes)
        val groups =
            edges.filter { routes.containsKey(it.id) && it.sourceId != it.targetId }.groupBy {
                minOf(it.sourceId, it.targetId) to
                    maxOf(it.sourceId, it.targetId)
            }
        for ((key, members) in groups.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            if (members.size < 2) continue
            val a = nodes[key.first] ?: continue
            val b = nodes[key.second] ?: continue
            val ax = a.origin.x + a.size.width / 2f
            val ay = a.origin.y + a.size.height / 2f
            val bx = b.origin.x + b.size.width / 2f
            val by = b.origin.y + b.size.height / 2f
            val vertical = abs(by - ay) >= abs(bx - ax)
            // Already apart? Then leave the layout engine's routes alone.
            val mids = members.map { mid(pts = polylineOf(routes.getValue(it.id)), vertical = vertical) }
            if (mids.sorted().zipWithNext().all { (p, q) -> q - p >= PARALLEL_GAP_PX - 0.5f }) continue
            val n = members.size
            val ordered = members.sortedWith(compareBy({ it.sourceId != key.first }, { it.text }, { it.id }))
            val shifted = LinkedHashMap<String, EdgeRoute>()
            val offsets = LinkedHashMap<String, Float>()
            var ok = true
            for ((k, m) in ordered.withIndex()) {
                val d = (k - (n - 1) / 2f) * PARALLEL_GAP_PX
                val r =
                    shiftAcross(
                        route = routes.getValue(m.id),
                        d = d,
                        vertical = vertical,
                        ends = listOf(a, b, nodes[m.sourceId], nodes[m.targetId]),
                    )
                if (r == null) {
                    ok = false
                    break
                }
                shifted[m.id] = r
                offsets[m.id] = d
            }
            if (!ok) continue
            out.putAll(shifted)
            if (n == 2) {
                // The corridor between the two lines belongs to neither label.
                val pa = polylineOf(shifted.getValue(ordered[0].id))
                val pb = polylineOf(shifted.getValue(ordered[1].id))
                val strip = corridor(a = pa, b = pb, vertical = vertical)
                if (strip != null) for (m in ordered) avoid.getOrPut(m.id) { ArrayList() } += strip
            }
        }
        return out
    }

    private fun mid(
        pts: List<Point>,
        vertical: Boolean,
    ): Float {
        val xs = pts.map { if (vertical) it.x else it.y }
        return (xs.min() + xs.max()) / 2f
    }

    private fun corridor(
        a: List<Point>,
        b: List<Point>,
        vertical: Boolean,
    ): Rect? {
        val ma = mid(pts = a, vertical = vertical)
        val mb = mid(pts = b, vertical = vertical)
        val lo = min(ma, mb) + 1.5f
        val hi = max(ma, mb) - 1.5f
        if (hi <= lo) return null
        val all = a + b
        return if (vertical) {
            val y0 = all.minOf { it.y }
            val y1 = all.maxOf { it.y }
            Rect(origin = Point(x = lo, y = y0), size = Size(width = hi - lo, height = y1 - y0))
        } else {
            val x0 = all.minOf { it.x }
            val x1 = all.maxOf { it.x }
            Rect(origin = Point(x = x0, y = lo), size = Size(width = x1 - x0, height = hi - lo))
        }
    }

    /**
     * Moves [route] by [d] across the axis of the pair. An end stays on its node border: it slides along the border, or
     * the whole shift is refused (`null`) when an end does not sit on a border that allows sliding.
     */
    private fun shiftAcross(
        route: EdgeRoute,
        d: Float,
        vertical: Boolean,
        ends: List<Rect?>,
    ): EdgeRoute? {
        if (route is EdgeRoute.Bezier) return null
        val pts = polylineOf(route)
        val boxes = ends.filterNotNull()

        fun Point.moved() = if (vertical) copy(x = x + d) else copy(y = y + d)

        fun onBorder(p: Point): Boolean =
            boxes.any { r ->
                val inX = p.x >= r.origin.x - 1.5f && p.x <= r.origin.x + r.size.width + 1.5f
                val inY = p.y >= r.origin.y - 1.5f && p.y <= r.origin.y + r.size.height + 1.5f
                if (vertical) {
                    inX && (abs(p.y - r.origin.y) < 1.5f || abs(p.y - (r.origin.y + r.size.height)) < 1.5f)
                } else {
                    inY && (abs(p.x - r.origin.x) < 1.5f || abs(p.x - (r.origin.x + r.size.width)) < 1.5f)
                }
            }

        val first = pts.first()
        val last = pts.last()
        if (!onBorder(first) || !onBorder(last)) return null

        fun clampToBorder(p: Point): Point {
            val m = p.moved()
            val box =
                boxes.firstOrNull { r ->
                    if (vertical) {
                        m.x in (r.origin.x - 0.01f)..(r.origin.x + r.size.width + 0.01f)
                    } else {
                        m.y in
                            (r.origin.y - 0.01f)..(r.origin.y + r.size.height + 0.01f)
                    }
                }
            return if (box != null) m else p
        }
        val ns = clampToBorder(first)
        val nt = clampToBorder(last)
        return when (route) {
            is EdgeRoute.Direct -> route.copy(source = ns, target = nt)
            is EdgeRoute.OrthogonalRounded -> route.copy(source = ns, target = nt, waypoints = route.waypoints.map { it.moved() })
            is EdgeRoute.TreeRounded -> route.copy(source = ns, target = nt, waypoints = route.waypoints.map { it.moved() })
            is EdgeRoute.Bezier -> null
        }
    }

    /** Length of the route polyline. */
    fun lengthOf(pts: List<Point>): Float {
        var s = 0f
        for (i in 0 until pts.size - 1) {
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            s += sqrt(dx * dx + dy * dy)
        }
        return s
    }

    fun polylineOf(route: EdgeRoute): List<Point> =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> listOf(route.source, route.target)
        }
}
