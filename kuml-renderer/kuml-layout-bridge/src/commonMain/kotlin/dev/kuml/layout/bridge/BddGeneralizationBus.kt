package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutDirection
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.NodeId
import dev.kuml.layout.NodeLayout
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bus-style generalisation fans for block definition diagrams (wave 2E-b).
 *
 * A block with at least [MIN_SPECIALISATIONS] specialisations that has no other edge is moved centred above (flow
 * `Up`: below) the group of its specialisations, and the generalisation edges are redrawn as one bus: every edge
 * leaves its specialisation at the near side, runs through the gap between two columns to a common horizontal bus
 * and ends at the same point of the general block - so the hollow triangles coincide and one triangle remains. A
 * specialisation of a second or later row reaches the bus through the gap between two columns of the rows in
 * front of it (never across a box). The pass is deterministic and skips a fan whose moved block would touch another
 * node. Horizontal flows (Left / Right) keep the layered engine's routes.
 */
internal object BddGeneralizationBus {
    const val MIN_SPECIALISATIONS = 3
    private const val BUS_GAP_PX = 28f
    private const val GUTTER_MARGIN_PX = 6f
    private const val OUTER_GUTTER_PX = 20f
    private const val CLEARANCE_PX = 8f
    private const val SPREAD_PX = 5f

    fun apply(
        result: LayoutResult,
        graph: LayoutGraph,
        direction: LayoutDirection,
    ): LayoutResult {
        if (direction != LayoutDirection.TopToBottom && direction != LayoutDirection.BottomToTop) return result
        val down = direction == LayoutDirection.TopToBottom
        val gen = graph.edges.filter { it.id.value.startsWith("gen:") && it.source.nodeId != it.target.nodeId }
        val byGeneral = gen.groupBy { it.target.nodeId }
        val nodes = LinkedHashMap(result.nodes)
        val edges = LinkedHashMap(result.edges)
        var changed = false
        for ((general, fan) in byGeneral.entries.sortedBy { it.key.value }) {
            val specIds = fan.map { it.source.nodeId }.distinct()
            if (specIds.size < MIN_SPECIALISATIONS) continue
            // The block must have no edge but the fan, and the specialisations must not be general blocks of a fan too.
            val onlyFan =
                graph.edges.none { e ->
                    (e.source.nodeId == general || e.target.nodeId == general) && e !in fan
                }
            if (!onlyFan) continue
            val genBounds = nodes[general]?.bounds ?: continue
            val specBounds = specIds.associateWith { nodes[it]?.bounds ?: return@apply result }
            val moved = placeAndRoute(general = general, genBounds = genBounds, specBounds = specBounds, fan = fan, down = down) ?: continue
            // skip when the moved block touches another node
            val others = nodes.filter { it.key != general && it.key !in specIds }.values.map { it.bounds }
            if (others.any { overlaps(a = it, b = moved.first, margin = CLEARANCE_PX) }) continue
            nodes[general] = NodeLayout(bounds = moved.first, ports = emptyMap(), labels = emptyMap())
            moved.second.forEach { (id, route) -> edges[id] = route }
            changed = true
        }
        if (!changed) return result
        return tighten(original = result, nodes = nodes, edges = edges)
    }

    private fun placeAndRoute(
        general: NodeId,
        genBounds: Rect,
        specBounds: Map<NodeId, Rect>,
        fan: List<dev.kuml.layout.LayoutEdge>,
        down: Boolean,
    ): Pair<Rect, Map<EdgeId, EdgeRoute>>? {
        fun u(y: Float) = if (down) y else -y

        fun back(u: Float) = if (down) u else -u

        // flow coordinate: grows away from the general block
        fun near(r: Rect) = if (down) r.origin.y else u(r.origin.y + r.size.height)

        fun far(r: Rect) = if (down) r.origin.y + r.size.height else u(r.origin.y)

        // rows: merge the specialisations by overlapping extent on the flow axis
        val ordered = specBounds.entries.sortedWith(compareBy({ near(it.value) }, { it.key.value }))
        val rows = ArrayList<MutableList<Map.Entry<NodeId, Rect>>>()
        var rowFar = Float.NEGATIVE_INFINITY
        for (e in ordered) {
            if (rows.isEmpty() || near(e.value) >= rowFar) {
                rows += mutableListOf(e)
                rowFar = far(e.value)
            } else {
                rows.last() += e
                rowFar = max(rowFar, far(e.value))
            }
        }
        for (r in rows) r.sortBy { it.value.origin.x }
        val row1Near = rows.first().minOf { near(it.value) }
        val busU = row1Near - BUS_GAP_PX
        val minX = specBounds.values.minOf { it.origin.x }
        val maxX = specBounds.values.maxOf { it.origin.x + it.size.width }
        val hx = (minX + maxX) / 2f
        val newGen =
            if (down) {
                Rect(
                    origin = Point(x = hx - genBounds.size.width / 2f, y = back(busU - BUS_GAP_PX) - genBounds.size.height),
                    size = genBounds.size,
                )
            } else {
                Rect(origin = Point(x = hx - genBounds.size.width / 2f, y = back(busU - BUS_GAP_PX)), size = genBounds.size)
            }
        val trunkY = if (down) newGen.origin.y + newGen.size.height else newGen.origin.y
        val trunk = Point(x = hx, y = trunkY)
        val routes = LinkedHashMap<EdgeId, EdgeRoute>()
        val gutterUse = HashMap<Int, Int>()
        val edgeBySpec = fan.associateBy { it.source.nodeId }
        for ((ri, row) in rows.withIndex()) {
            for (entry in row) {
                val b = entry.value
                val sx = b.origin.x + b.size.width / 2f
                val sy = if (down) b.origin.y else b.origin.y + b.size.height
                val pts = ArrayList<Point>()
                if (ri > 0) {
                    val prevFar = rows[ri - 1].maxOf { far(it.value) }
                    val midU = (prevFar + near(b)) / 2f
                    val gx = gutterX(rows = rows.subList(0, ri), x = sx, use = gutterUse)
                    pts += Point(x = sx, y = back(midU))
                    pts += Point(x = gx, y = back(midU))
                    pts += Point(x = gx, y = back(busU))
                } else {
                    pts += Point(x = sx, y = back(busU))
                }
                pts += Point(x = hx, y = back(busU))
                val edge = edgeBySpec[entry.key] ?: continue
                routes[edge.id] =
                    EdgeRoute.OrthogonalRounded(
                        source = Point(x = sx, y = sy),
                        target = trunk,
                        waypoints = dedupe(pts),
                        cornerRadiusPx = 0f,
                    )
            }
        }
        return newGen to routes
    }

    /** The gap between two columns of [rows] (or the outer margin) nearest to [x]; several edges through one gap fan out. */
    private fun gutterX(
        rows: List<List<Map.Entry<NodeId, Rect>>>,
        x: Float,
        use: MutableMap<Int, Int>,
    ): Float {
        val spans =
            rows
                .flatten()
                .map { (it.value.origin.x - GUTTER_MARGIN_PX) to (it.value.origin.x + it.value.size.width + GUTTER_MARGIN_PX) }
                .sortedBy { it.first }
        val merged = ArrayList<Pair<Float, Float>>()
        for (s in spans) {
            if (merged.isNotEmpty() && s.first <= merged.last().second) {
                merged[merged.size - 1] = merged.last().first to max(merged.last().second, s.second)
            } else {
                merged += s
            }
        }
        val gaps = ArrayList<Float>()
        gaps += merged.first().first - OUTER_GUTTER_PX
        for (i in 0 until merged.size - 1) {
            if (merged[i + 1].first - merged[i].second >= 8f) gaps += (merged[i].second + merged[i + 1].first) / 2f
        }
        gaps += merged.last().second + OUTER_GUTTER_PX
        val index = gaps.indices.minBy { abs(gaps[it] - x) }
        val k = (use[index] ?: 0)
        use[index] = k + 1
        val half = if (index == 0 || index == gaps.size - 1) 0f else (merged[index].first - merged[index - 1].second) / 2f - 3f
        val offset = min(k * SPREAD_PX, max(0f, half)) * (if (k % 2 == 0) 1f else -1f)
        return gaps[index] + offset
    }

    private fun dedupe(points: List<Point>): List<Point> {
        val out = ArrayList<Point>()
        for (p in points) if (out.lastOrNull() != p) out += p
        return out
    }

    private fun overlaps(
        a: Rect,
        b: Rect,
        margin: Float,
    ): Boolean =
        a.origin.x < b.origin.x + b.size.width + margin &&
            a.origin.x + a.size.width + margin > b.origin.x &&
            a.origin.y < b.origin.y + b.size.height + margin &&
            a.origin.y + a.size.height + margin > b.origin.y

    /** Shifts everything so the old margins around the content stay and the canvas fits the content tightly. */
    internal fun tighten(
        original: LayoutResult,
        nodes: Map<NodeId, NodeLayout>,
        edges: Map<EdgeId, EdgeRoute>,
    ): LayoutResult {
        val oldMinX = original.nodes.values.minOf { it.bounds.origin.x }
        val oldMinY = original.nodes.values.minOf { it.bounds.origin.y }
        val oldMaxX = original.nodes.values.maxOf { it.bounds.origin.x + it.bounds.size.width }
        val oldMaxY = original.nodes.values.maxOf { it.bounds.origin.y + it.bounds.size.height }
        val right = original.canvas.width - oldMaxX
        val bottom = original.canvas.height - oldMaxY
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        fun see(p: Point) {
            minX = min(minX, p.x)
            minY = min(minY, p.y)
            maxX = max(maxX, p.x)
            maxY = max(maxY, p.y)
        }
        nodes.values.forEach {
            see(it.bounds.origin)
            see(Point(x = it.bounds.origin.x + it.bounds.size.width, y = it.bounds.origin.y + it.bounds.size.height))
        }
        edges.values.forEach { r ->
            see(r.source)
            see(r.target)
            if (r is EdgeRoute.OrthogonalRounded) r.waypoints.forEach(::see)
        }
        val dx = oldMinX - minX
        val dy = oldMinY - minY
        val shifted =
            original.copy(
                nodes = nodes,
                edges = edges,
                canvas =
                    Size(
                        width = maxX - minX + oldMinX + right,
                        height =
                            maxY - minY + oldMinY + bottom,
                    ),
            )
        return shifted.translatedBy(dx = dx, dy = dy)
    }

    private fun LayoutResult.translatedBy(
        dx: Float,
        dy: Float,
    ): LayoutResult {
        if (dx == 0f && dy == 0f) return this

        fun p(pt: Point) = Point(x = pt.x + dx, y = pt.y + dy)
        return copy(
            nodes =
                nodes.mapValues { (_, n) ->
                    NodeLayout(
                        bounds = Rect(origin = p(n.bounds.origin), size = n.bounds.size),
                        ports = n.ports.mapValues { p(it.value) },
                        labels = n.labels.mapValues { Rect(origin = p(it.value.origin), size = it.value.size) },
                    )
                },
            edges =
                edges.mapValues { (_, r) ->
                    when (r) {
                        is EdgeRoute.Direct -> EdgeRoute.Direct(source = p(r.source), target = p(r.target))
                        is EdgeRoute.OrthogonalRounded ->
                            r.copy(
                                source = p(r.source),
                                target = p(r.target),
                                waypoints = r.waypoints.map(::p),
                            )
                        is EdgeRoute.TreeRounded -> r.copy(source = p(r.source), target = p(r.target), waypoints = r.waypoints.map(::p))
                        is EdgeRoute.Bezier -> r.copy(source = p(r.source), target = p(r.target), controlPoints = r.controlPoints.map(::p))
                    }
                },
            groups = groups.mapValues { (_, g) -> g.copy(bounds = Rect(origin = p(g.bounds.origin), size = g.bounds.size)) },
        )
    }
}
