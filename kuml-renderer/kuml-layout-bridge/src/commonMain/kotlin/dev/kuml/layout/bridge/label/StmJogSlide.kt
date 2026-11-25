@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.EDGE_EDGE_TARGET_GAP_PX
import dev.kuml.layout.bridge.EDGE_NODE_MIN_GAP_PX
import dev.kuml.layout.bridge.MIN_NODE_EXTENT_FOR_TWO_LANES_PX
import dev.kuml.layout.bridge.PARALLEL_MIN_OVERLAP_PX
import dev.kuml.layout.bridge.countCrossings
import dev.kuml.layout.bridge.edgeNodeGap
import dev.kuml.layout.bridge.parallelGap
import dev.kuml.layout.bridge.parallelOverlap
import kotlin.math.abs

/**
 * PAL Welle O: moves the single short jog of an orthogonal STM route inside its channel, so that a transition label that found no
 * free position gets a longer straight own segment. The source and the target of the route never move; only the flow coordinate of
 * the two waypoints changes (the `y` of a vertical flow, the `x` of a horizontal flow).
 *
 * A route qualifies when it has exactly the shape long / jog / long: two waypoints, the first and the last segment parallel to the
 * flow axis, the middle segment (the jog) perpendicular to it and at most [MAX_JOG_PX] long. [candidates] offers two fixed
 * positions (the jog at the target end, then at the source end) and [acceptsGeometry] is the pure geometry gate for one of them.
 * The label plan itself is checked by the caller (see `KumlSvgRenderer.stmJogSlideLayout`), because only the renderer can plan labels.
 *
 * No state, no logging; every function is deterministic and bounded by the number of routes the caller passes.
 */
public object StmJogSlide {
    /**
     * Longest jog (px) that may be slid. Two [EDGE_EDGE_TARGET_GAP_PX] (32 px): the Doc 55 transition T56 jogs by 24.75 px, so a
     * 24 px limit would exclude exactly the route this wave is for.
     */
    public const val MAX_JOG_PX: Float = 2f * EDGE_EDGE_TARGET_GAP_PX

    /** Distance (px) the slid jog keeps from the end of the route: [EDGE_NODE_MIN_GAP_PX], the arrow head of the target is 12 px. */
    public const val END_INSET_PX: Float = EDGE_NODE_MIN_GAP_PX

    /** Most edges one render tries to slide (every edge costs at most two further label plans). */
    public const val MAX_EDGES: Int = 16

    private const val EPS = 0.5f

    /** A route of the shape long / jog / long on the flow axis: [vertical] flow, the jog at [jogAt], running from [from] to [to]. */
    internal data class Shape(
        val vertical: Boolean,
        val jogAt: Float,
        val from: Float,
        val to: Float,
    )

    private fun waypointsOf(route: EdgeRoute): List<Point>? =
        when (route) {
            is EdgeRoute.OrthogonalRounded -> route.waypoints
            is EdgeRoute.TreeRounded -> route.waypoints
            else -> null
        }

    /** The shape of [route], or `null` for every other route (no polyline, not exactly two waypoints, not monotone, jog too long). */
    internal fun shapeOf(
        route: EdgeRoute,
        maxJogPx: Float = MAX_JOG_PX,
    ): Shape? {
        val wp = waypointsOf(route) ?: return null
        if (wp.size != 2) return null
        val s = route.source
        val a = wp[0]
        val b = wp[1]
        val t = route.target
        if (!listOf(s, a, b, t).all { it.x.isFinite() && it.y.isFinite() }) return null
        // Vertical flow: x constant on the first and the last segment, y constant on the jog.
        if (abs(s.x - a.x) < EPS && abs(a.y - b.y) < EPS && abs(b.x - t.x) < EPS) {
            val jog = abs(a.x - b.x)
            if (jog <= EPS || jog > maxJogPx) return null
            return monotone(vertical = true, from = s.y, jogAt = a.y, to = t.y)
        }
        if (abs(s.y - a.y) < EPS && abs(a.x - b.x) < EPS && abs(b.y - t.y) < EPS) {
            val jog = abs(a.y - b.y)
            if (jog <= EPS || jog > maxJogPx) return null
            return monotone(vertical = false, from = s.x, jogAt = a.x, to = t.x)
        }
        return null
    }

    private fun monotone(
        vertical: Boolean,
        from: Float,
        jogAt: Float,
        to: Float,
    ): Shape? {
        val sign = if (to >= from) 1f else -1f
        // The jog lies strictly between both ends, so the route does not turn back on itself.
        if ((jogAt - from) * sign <= EPS || (to - jogAt) * sign <= EPS) return null
        return Shape(vertical = vertical, jogAt = jogAt, from = from, to = to)
    }

    private fun withJogAt(
        route: EdgeRoute,
        shape: Shape,
        jogAt: Float,
    ): EdgeRoute? {
        val wp = waypointsOf(route) ?: return null
        val moved =
            wp.map { p -> if (shape.vertical) Point(x = p.x, y = jogAt) else Point(x = jogAt, y = p.y) }
        return when (route) {
            is EdgeRoute.OrthogonalRounded -> route.copy(waypoints = moved)
            is EdgeRoute.TreeRounded -> route.copy(waypoints = moved)
            else -> null
        }
    }

    /**
     * The routes to try for [route], in a fixed order: the jog at the target end ([END_INSET_PX] before the target), then at the
     * source end ([END_INSET_PX] behind the source). Empty when [route] does not have the shape, when the channel is shorter than
     * twice the inset, or when a position equals the current one.
     */
    public fun candidates(route: EdgeRoute): List<EdgeRoute> {
        val shape = shapeOf(route) ?: return emptyList()
        val length = abs(shape.to - shape.from)
        if (length < 2f * END_INSET_PX + EPS) return emptyList()
        val sign = if (shape.to >= shape.from) 1f else -1f
        val positions = listOf(shape.to - sign * END_INSET_PX, shape.from + sign * END_INSET_PX)
        return positions
            .filter { abs(it - shape.jogAt) >= EPS }
            .distinct()
            .mapNotNull { withJogAt(route = route, shape = shape, jogAt = it) }
    }

    /** How far the jog moved from [current] to [candidate] on the flow axis; `null` when either route does not have the shape. */
    public fun delta(
        current: EdgeRoute,
        candidate: EdgeRoute,
    ): Float? {
        val a = shapeOf(current, maxJogPx = Float.MAX_VALUE) ?: return null
        val b = shapeOf(candidate, maxJogPx = Float.MAX_VALUE) ?: return null
        return if (a.vertical == b.vertical) b.jogAt - a.jogAt else null
    }

    /**
     * Transfers a slide to the raw layout route: the same [delta] on the flow coordinate of both waypoints. `null` when [raw] does
     * not have the shape (the caller then drops the candidate). The jog length of [raw] is not limited here, because the route the
     * caller compares afterwards (point by point) is the transformed one.
     */
    public fun shiftRaw(
        raw: EdgeRoute,
        delta: Float,
    ): EdgeRoute? {
        val shape = shapeOf(route = raw, maxJogPx = Float.MAX_VALUE) ?: return null
        if (!delta.isFinite()) return null
        return withJogAt(route = raw, shape = shape, jogAt = shape.jogAt + delta)
    }

    private fun pointsOf(route: EdgeRoute): List<Point> = Sysml2DiagramLabelPlan.stmPolylineOf(route)

    private fun framePolyline(r: Rect): List<Point> {
        val x0 = r.origin.x
        val y0 = r.origin.y
        val x1 = x0 + r.size.width
        val y1 = y0 + r.size.height
        return listOf(Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1), Point(x0, y0))
    }

    /**
     * Geometry gate for replacing `routes[id]` with [candidate]. Every other route stays as it is, so only pairs that involve [id]
     * are compared. The candidate is accepted when, against the current route:
     *  - the number of crossings with every other route and every composite frame does not rise,
     *  - the number of other routes it runs closer than [EDGE_EDGE_TARGET_GAP_PX] beside (same exemptions as the layout's
     *    clearance check: bundled stubs of a shared node, a node too small for two lanes, a node in between) does not rise,
     *  - the new jog segment keeps at least [EDGE_EDGE_TARGET_GAP_PX] from every parallel segment of a foreign route (strict),
     *  - the number of foreign nodes it comes closer to than [EDGE_NODE_MIN_GAP_PX] does not rise,
     *  - every end point of another route that lay on the current route lies on the candidate as well (note anchors).
     *
     * [ends] maps an edge id to its (source node, target node); [nodes] are the states and pseudostates, [frames] the composite
     * frames, all in the coordinates of [routes].
     */
    public fun acceptsGeometry(
        id: String,
        candidate: EdgeRoute,
        routes: Map<String, EdgeRoute>,
        ends: Map<String, Pair<String, String>>,
        nodes: Map<String, Rect>,
        frames: List<Rect>,
    ): Boolean {
        val current = routes[id] ?: return false
        if (shapeOf(current) == null || shapeOf(candidate, maxJogPx = Float.MAX_VALUE) == null) return false
        val before = pointsOf(current)
        val after = pointsOf(candidate)
        if (after.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val others = LinkedHashMap<String, List<Point>>()
        for ((k, r) in routes) if (k != id) others[k] = pointsOf(r)

        if (crossings(after, others, frames) > crossings(before, others, frames)) return false
        if (parallelViolations(id, after, others, ends, nodes) > parallelViolations(id, before, others, ends, nodes)) return false
        if (!jogKeepsDistance(candidate = candidate, others = others)) return false
        val own = ends[id]?.toList().orEmpty().toSet()
        val foreign = nodes.filterKeys { it !in own }
        if (nodeViolations(after, foreign) > nodeViolations(before, foreign)) return false
        for (p in others.values.flatMap { listOf(it.first(), it.last()) }) {
            if (distanceToPolyline(p, before) <= EPS && distanceToPolyline(p, after) > EPS) return false
        }
        return true
    }

    private fun crossings(
        pts: List<Point>,
        others: Map<String, List<Point>>,
        frames: List<Rect>,
    ): Int {
        var n = 0
        for (o in others.values) n += countCrossings(listOf(pts, o))
        for (f in frames) n += countCrossings(listOf(pts, framePolyline(f)))
        return n
    }

    private fun parallelViolations(
        id: String,
        pts: List<Point>,
        others: Map<String, List<Point>>,
        ends: Map<String, Pair<String, String>>,
        nodes: Map<String, Rect>,
    ): Int {
        val mine = ends[id]?.toList().orEmpty().toSet()
        var count = 0
        for ((otherId, otherPts) in others) {
            val shared = mine.intersect(ends[otherId]?.toList().orEmpty().toSet())
            var violated = false
            for (sa in pts.zipWithNext()) {
                for (sb in otherPts.zipWithNext()) {
                    val gap = parallelGap(sa, sb) ?: continue
                    if (parallelOverlap(sa, sb) < PARALLEL_MIN_OVERLAP_PX) continue
                    if (gap >= EDGE_EDGE_TARGET_GAP_PX) continue
                    if (shared.isNotEmpty() && gap < EPS) continue
                    val vertical = abs(sa.first.x - sa.second.x) < EPS
                    if (shared.any { n ->
                            nodes[n]?.let { (if (vertical) it.size.width else it.size.height) < MIN_NODE_EXTENT_FOR_TWO_LANES_PX } == true
                        }
                    ) {
                        continue
                    }
                    if (nodeBetween(sa, sb, nodes.values)) continue
                    violated = true
                }
            }
            if (violated) count++
        }
        return count
    }

    /** The jog (middle segment) of [candidate] keeps [EDGE_EDGE_TARGET_GAP_PX] from every parallel segment of the other routes. */
    private fun jogKeepsDistance(
        candidate: EdgeRoute,
        others: Map<String, List<Point>>,
    ): Boolean {
        val wp = waypointsOf(candidate) ?: return false
        val jog = wp[0] to wp[1]
        for (o in others.values) {
            for (seg in o.zipWithNext()) {
                val gap = parallelGap(jog, seg) ?: continue
                if (gap < EDGE_EDGE_TARGET_GAP_PX) return false
            }
        }
        return true
    }

    private fun nodeViolations(
        pts: List<Point>,
        foreign: Map<String, Rect>,
    ): Int = foreign.values.count { r -> (edgeNodeGap(route = pts, rect = r) ?: Float.MAX_VALUE) < EDGE_NODE_MIN_GAP_PX }

    private fun distanceToPolyline(
        p: Point,
        pts: List<Point>,
    ): Float {
        var best = Float.MAX_VALUE
        for ((a, b) in pts.zipWithNext()) {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
            val qx = a.x + dx * t
            val qy = a.y + dy * t
            val d = kotlin.math.sqrt((p.x - qx) * (p.x - qx) + (p.y - qy) * (p.y - qy))
            if (d < best) best = d
        }
        return best
    }

    private fun nodeBetween(
        sa: Pair<Point, Point>,
        sb: Pair<Point, Point>,
        nodes: Collection<Rect>,
    ): Boolean {
        val x0 = minOf(sa.first.x, sa.second.x, sb.first.x, sb.second.x)
        val x1 = maxOf(sa.first.x, sa.second.x, sb.first.x, sb.second.x)
        val y0 = minOf(sa.first.y, sa.second.y, sb.first.y, sb.second.y)
        val y1 = maxOf(sa.first.y, sa.second.y, sb.first.y, sb.second.y)
        return nodes.any { n ->
            n.origin.x < x1 && n.origin.x + n.size.width > x0 && n.origin.y < y1 && n.origin.y + n.size.height > y0
        }
    }
}
