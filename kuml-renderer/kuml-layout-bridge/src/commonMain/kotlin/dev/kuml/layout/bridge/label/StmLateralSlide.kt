@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.label

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.EDGE_EDGE_TARGET_GAP_PX
import dev.kuml.layout.bridge.EDGE_NODE_MIN_GAP_PX
import dev.kuml.layout.bridge.PARALLEL_MIN_OVERLAP_PX
import dev.kuml.layout.bridge.parallelGap
import dev.kuml.layout.bridge.parallelOverlap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PAL Welle R: moves a long straight stretch of an orthogonal STM route sideways (perpendicular to the flow axis), so that a
 * transition label whose corridor is too narrow for even the narrowest wrap gets more room on one side. [StmJogSlide] only moves
 * the jog along the flow axis; this one changes the lateral position of a long segment, and with it the position where the route
 * meets its node (the anchor slides along the node side).
 *
 * Two shapes qualify: a straight route (no waypoints, parallel to the flow axis; both ends move together, the route stays
 * straight) and a long / jog / long route (exactly two waypoints, see [StmJogSlide.shapeOf]; one long segment moves with its end
 * point and waypoint, the jog grows or shrinks and must stay on its side, at most [MAX_SHIFT_PX] long; a jog that shrinks to
 * nothing makes the route straight). Every other route has no candidates.
 *
 * [candidates] lists the shifts in a fixed order (-8, +8, -16, +16, ... up to [MAX_SHIFT_PX]) and [acceptsGeometry] is the pure
 * geometry gate for one of them. The label plan itself is checked by the caller (see `KumlSvgRenderer.stmJogSlideLayout`).
 *
 * No state, no logging; every function is deterministic and bounded by the number of routes the caller passes.
 */
public object StmLateralSlide {
    /** Step (px) between two candidate shifts. */
    public const val STEP_PX: Float = 8f

    /** Largest shift (px) of a segment, and the longest jog that may result: the same bound as [StmJogSlide.MAX_JOG_PX]. */
    public const val MAX_SHIFT_PX: Float = StmJogSlide.MAX_JOG_PX

    /** Distance (px) a moved anchor keeps from the corners of its node side. */
    public const val END_INSET_PX: Float = EDGE_NODE_MIN_GAP_PX

    /** Distance (px) a moved anchor keeps from the other anchors of the same node. */
    public const val PORT_GAP_PX: Float = EDGE_EDGE_TARGET_GAP_PX

    /** Most routes [acceptsGeometry] looks at (hostile-input cap). */
    private const val MAX_ROUTES = 512

    private const val EPS = StmJogSlide.EPS

    /** Which part of the route moves. */
    public enum class Part {
        /** A straight route: both ends move. */
        WHOLE,

        /** The long segment at the source (its end point and its waypoint). */
        SOURCE_SEGMENT,

        /** The long segment at the target (its end point and its waypoint). */
        TARGET_SEGMENT,
    }

    /** A lateral move: [part] of the route by [delta] px perpendicular to the flow axis (signed). */
    public data class Slide(
        val part: Part,
        val delta: Float,
    )

    /** The flow axis of a route ([vertical]: x is the lateral coordinate) and whether it is straight. */
    private class Form(
        val vertical: Boolean,
        val straight: Boolean,
        val sourceLength: Float,
        val targetLength: Float,
    )

    private fun lateral(
        p: Point,
        vertical: Boolean,
    ): Float = if (vertical) p.x else p.y

    private fun flow(
        p: Point,
        vertical: Boolean,
    ): Float = if (vertical) p.y else p.x

    private fun moved(
        p: Point,
        vertical: Boolean,
        delta: Float,
    ): Point = if (vertical) Point(x = p.x + delta, y = p.y) else Point(x = p.x, y = p.y + delta)

    private fun formOf(
        route: EdgeRoute,
        maxJogPx: Float = MAX_SHIFT_PX,
    ): Form? {
        val wp = StmJogSlide.waypointsOf(route)
        val finite = listOf(route.source, route.target).all { it.x.isFinite() && it.y.isFinite() }
        if (!finite) return null
        if (route is EdgeRoute.Direct || (wp != null && wp.isEmpty())) {
            val dx = abs(route.source.x - route.target.x)
            val dy = abs(route.source.y - route.target.y)
            return when {
                dx < EPS && dy > EPS -> Form(vertical = true, straight = true, sourceLength = dy, targetLength = dy)
                dy < EPS && dx > EPS -> Form(vertical = false, straight = true, sourceLength = dx, targetLength = dx)
                else -> null
            }
        }
        val shape = StmJogSlide.shapeOf(route = route, maxJogPx = maxJogPx) ?: return null
        return Form(
            vertical = shape.vertical,
            straight = false,
            sourceLength = abs(shape.jogAt - shape.from),
            targetLength = abs(shape.to - shape.jogAt),
        )
    }

    /** [route] with its [part] moved by [delta]; `null` when the route does not have the right shape or the result is not valid. */
    private fun withShift(
        route: EdgeRoute,
        form: Form,
        part: Part,
        delta: Float,
    ): EdgeRoute? {
        if (!delta.isFinite()) return null
        val v = form.vertical
        if (form.straight != (part == Part.WHOLE)) return null
        val source = if (part != Part.TARGET_SEGMENT) moved(route.source, v, delta) else route.source
        val target = if (part != Part.SOURCE_SEGMENT) moved(route.target, v, delta) else route.target
        var way = StmJogSlide.waypointsOf(route).orEmpty()
        if (!form.straight) {
            if (way.size != 2) return null
            val a = if (part == Part.SOURCE_SEGMENT) moved(way[0], v, delta) else way[0]
            val b = if (part == Part.TARGET_SEGMENT) moved(way[1], v, delta) else way[1]
            val oldJog = lateral(way[1], v) - lateral(way[0], v)
            val newJog = lateral(b, v) - lateral(a, v)
            // The jog keeps its side and stays short; one that shrinks to nothing turns the route straight.
            if (abs(newJog) > MAX_SHIFT_PX + EPS) return null
            way =
                if (abs(newJog) < EPS) {
                    emptyList()
                } else if (newJog * oldJog > 0f) {
                    listOf(a, b)
                } else {
                    return null
                }
        }
        return when (route) {
            is EdgeRoute.Direct -> route.copy(source = source, target = target)
            is EdgeRoute.OrthogonalRounded -> route.copy(source = source, target = target, waypoints = way)
            is EdgeRoute.TreeRounded -> route.copy(source = source, target = target, waypoints = way)
            is EdgeRoute.Bezier -> null
        }
    }

    /**
     * The routes to try for [route] in a fixed order ([delta] says which move a candidate stands for). Shifts
     * -8, +8, -16, +16, -24, +24, -32, +32; for a long / jog / long route first every shift of the longer segment (the target
     * segment when both are equally long), then every shift of the other one. Empty for every other shape.
     */
    public fun candidates(route: EdgeRoute): List<EdgeRoute> {
        val form = formOf(route) ?: return emptyList()
        val parts =
            when {
                form.straight -> listOf(Part.WHOLE)
                form.sourceLength > form.targetLength + EPS -> listOf(Part.SOURCE_SEGMENT, Part.TARGET_SEGMENT)
                else -> listOf(Part.TARGET_SEGMENT, Part.SOURCE_SEGMENT)
            }
        val steps = (1..(MAX_SHIFT_PX / STEP_PX).toInt()).flatMap { k -> listOf(-k * STEP_PX, k * STEP_PX) }
        val out = ArrayList<EdgeRoute>()
        for (part in parts) {
            for (d in steps) withShift(route = route, form = form, part = part, delta = d)?.let { out += it }
        }
        return out.distinct()
    }

    /**
     * The move that turns [current] into [candidate]; `null` when the flow coordinates of the ends changed, when both ends
     * moved by different amounts, or when the shapes do not fit (a straight route moves as a whole, a long / jog / long route one
     * segment at a time).
     */
    public fun delta(
        current: EdgeRoute,
        candidate: EdgeRoute,
    ): Slide? {
        val a = formOf(route = current, maxJogPx = Float.MAX_VALUE) ?: return null
        val b = formOf(route = candidate, maxJogPx = Float.MAX_VALUE) ?: return null
        if (a.vertical != b.vertical) return null
        val v = a.vertical
        val flowSame =
            abs(flow(current.source, v) - flow(candidate.source, v)) < EPS &&
                abs(flow(current.target, v) - flow(candidate.target, v)) < EPS
        if (!flowSame) return null
        val ds = lateral(candidate.source, v) - lateral(current.source, v)
        val dt = lateral(candidate.target, v) - lateral(current.target, v)
        if (!ds.isFinite() || !dt.isFinite()) return null
        val movedSource = abs(ds) >= EPS
        val movedTarget = abs(dt) >= EPS
        return when {
            movedSource && movedTarget -> if (a.straight && abs(ds - dt) < EPS) Slide(Part.WHOLE, ds) else null
            movedSource -> if (!a.straight) Slide(Part.SOURCE_SEGMENT, ds) else null
            movedTarget -> if (!a.straight) Slide(Part.TARGET_SEGMENT, dt) else null
            else -> null
        }
    }

    /**
     * Transfers a [slide] to the raw layout route: the same shift on the same part. `null` when [raw] does not have the shape the
     * slide needs (the caller then drops the candidate). The jog length of [raw] is not limited, because the route the caller
     * compares afterwards (point by point) is the transformed one.
     */
    public fun shiftRaw(
        raw: EdgeRoute,
        slide: Slide,
    ): EdgeRoute? {
        val form = formOf(route = raw, maxJogPx = Float.MAX_VALUE) ?: return null
        return withShift(route = raw, form = form, part = slide.part, delta = slide.delta)
    }

    private fun frameGapViolations(
        pts: List<Point>,
        frames: List<Rect>,
    ): Int {
        var n = 0
        for (f in frames) {
            val x0 = f.origin.x
            val y0 = f.origin.y
            val x1 = x0 + f.size.width
            val y1 = y0 + f.size.height
            for ((a, b) in pts.zipWithNext()) {
                val gap =
                    when {
                        abs(a.y - b.y) < EPS ->
                            if (overlapLength(a.x, b.x, x0, x1) >= PARALLEL_MIN_OVERLAP_PX) min(abs(a.y - y0), abs(a.y - y1)) else null
                        abs(a.x - b.x) < EPS ->
                            if (overlapLength(a.y, b.y, y0, y1) >= PARALLEL_MIN_OVERLAP_PX) min(abs(a.x - x0), abs(a.x - x1)) else null
                        else -> null
                    }
                if (gap != null && gap < EDGE_NODE_MIN_GAP_PX) n++
            }
        }
        return n
    }

    private fun overlapLength(
        a0: Float,
        a1: Float,
        b0: Float,
        b1: Float,
    ): Float = (min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1))).coerceAtLeast(0f)

    /** The segments of [pts] the move changed (the moved long segment, and the jog when there is one). */
    private fun changedSegments(
        pts: List<Point>,
        slide: Slide,
    ): List<Pair<Point, Point>> {
        val segs = pts.zipWithNext()
        return when {
            segs.size <= 1 -> segs
            slide.part == Part.SOURCE_SEGMENT -> segs.take(2)
            slide.part == Part.TARGET_SEGMENT -> segs.takeLast(2)
            else -> segs
        }
    }

    /**
     * Geometry gate for replacing `routes[id]` with [candidate]. Every other route stays as it is, so only pairs that involve [id]
     * are compared. The candidate is accepted when, against the current route:
     *  - the move is at most [MAX_SHIFT_PX] and the shapes fit (see [delta]),
     *  - the number of crossings with every other route and every composite frame does not rise,
     *  - the number of other routes it runs closer than [EDGE_EDGE_TARGET_GAP_PX] beside does not rise, and the changed segments
     *    keep at least [EDGE_EDGE_TARGET_GAP_PX] from every parallel segment of a foreign route (strict),
     *  - the number of foreign nodes it comes closer to than [EDGE_NODE_MIN_GAP_PX] does not rise,
     *  - the number of parallel runs along a composite frame side closer than [EDGE_NODE_MIN_GAP_PX] does not rise, and the changed
     *    segments keep that distance from every frame side they run along (strict),
     *  - every moved end point lies on its node side at least [END_INSET_PX] from the corners, and keeps [PORT_GAP_PX] from the
     *    other end points on the same node (or at least the distance it had before),
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
        if (routes.size > MAX_ROUTES) return false
        val slide = delta(current = current, candidate = candidate) ?: return false
        if (abs(slide.delta) > MAX_SHIFT_PX + EPS) return false
        val before = StmJogSlide.pointsOf(current)
        val after = StmJogSlide.pointsOf(candidate)
        if (after.size < 2 || after.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val others = LinkedHashMap<String, List<Point>>()
        for ((k, r) in routes) if (k != id) others[k] = StmJogSlide.pointsOf(r)

        if (StmJogSlide.crossings(after, others, frames) > StmJogSlide.crossings(before, others, frames)) return false
        if (StmJogSlide.parallelViolations(id, after, others, ends, nodes) >
            StmJogSlide.parallelViolations(id, before, others, ends, nodes)
        ) {
            return false
        }
        val changed = changedSegments(pts = after, slide = slide)
        for (o in others.values) {
            for (seg in o.zipWithNext()) {
                for (mine in changed) {
                    val gap = parallelGap(mine, seg) ?: continue
                    if (gap < EDGE_EDGE_TARGET_GAP_PX && parallelOverlap(mine, seg) >= PARALLEL_MIN_OVERLAP_PX) return false
                }
            }
        }
        val own = ends[id]?.toList().orEmpty().toSet()
        val foreign = nodes.filterKeys { it !in own }
        if (StmJogSlide.nodeViolations(after, foreign) > StmJogSlide.nodeViolations(before, foreign)) return false
        if (frameGapViolations(after, frames) > frameGapViolations(before, frames)) return false
        for (mine in changed) {
            if (frameGapViolations(listOf(mine.first, mine.second), frames) > 0) return false
        }
        if (!anchorsOk(id = id, current = current, candidate = candidate, slide = slide, others = others, ends = ends, nodes = nodes)) {
            return false
        }
        for (p in others.values.flatMap { listOf(it.first(), it.last()) }) {
            if (StmJogSlide.distanceToPolyline(p, before) <= EPS && StmJogSlide.distanceToPolyline(p, after) > EPS) return false
        }
        return true
    }

    /** The moved end points stay on their node side (with a corner inset) and keep their distance to the other end points there. */
    private fun anchorsOk(
        id: String,
        current: EdgeRoute,
        candidate: EdgeRoute,
        slide: Slide,
        others: Map<String, List<Point>>,
        ends: Map<String, Pair<String, String>>,
        nodes: Map<String, Rect>,
    ): Boolean {
        val form = formOf(route = current, maxJogPx = Float.MAX_VALUE) ?: return false
        val v = form.vertical
        val (sourceNode, targetNode) = ends[id] ?: return false
        val checks = ArrayList<Triple<String, Point, Point>>() // node, old point, new point
        if (slide.part != Part.TARGET_SEGMENT) checks += Triple(sourceNode, current.source, candidate.source)
        if (slide.part != Part.SOURCE_SEGMENT) checks += Triple(targetNode, current.target, candidate.target)
        for ((node, old, new) in checks) {
            val box = nodes[node] ?: return false
            val lo = (if (v) box.origin.x else box.origin.y) + END_INSET_PX
            val hi = (if (v) box.origin.x + box.size.width else box.origin.y + box.size.height) - END_INSET_PX
            val c = lateral(new, v)
            if (lo > hi || c < lo - EPS || c > hi + EPS) return false
            for ((otherId, pts) in others) {
                val otherEnds = ends[otherId] ?: continue
                val atNode = ArrayList<Point>()
                if (otherEnds.first == node) atNode += pts.first()
                if (otherEnds.second == node) atNode += pts.last()
                for (q in atNode) {
                    val newDist = dist(new, q)
                    if (newDist < PORT_GAP_PX && newDist < dist(old, q) - EPS) return false
                }
            }
        }
        return true
    }

    private fun dist(
        a: Point,
        b: Point,
    ): Float = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
}
