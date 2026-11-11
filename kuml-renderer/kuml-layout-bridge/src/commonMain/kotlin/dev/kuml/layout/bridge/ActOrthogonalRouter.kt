package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.port.IbdObstacleRouter
import dev.kuml.sysml2.ActivityNodeKind

/** Public facade over the obstacle router for callers outside this module (the SVG renderer's pin routing). */
public object ActObstacleRouting {
    /**
     * Orthogonal polyline from [from] to [to] that keeps 8 px away from [obstacles]. [fromNormal] / [toNormal] are the
     * outward directions at the two ends. Bounded by the router's own search limits.
     */
    public fun route(
        from: Point,
        fromNormal: Point,
        to: Point,
        toNormal: Point,
        obstacles: List<Rect>,
    ): List<Point> = IbdObstacleRouter.route(from = from, fromDir = fromNormal, to = to, toDir = toNormal, obstacles = obstacles)

    /** `true` when a segment of [path] crosses the interior of one of [obstacles]. */
    public fun hits(
        path: List<Point>,
        obstacles: List<Rect>,
    ): Boolean = IbdObstacleRouter.collisions(path = path, obstacles = obstacles) > 0
}

/** One flow with the geometry the router needs. */
internal data class ActEdgeEnds(
    val id: String,
    val source: String,
    val target: String,
)

/**
 * Orthogonal routing for swimlane-column activity layouts (PAL wave 2F).
 *
 * Forward edges leave at the bottom of the source and arrive at the top of the target (decision diamonds with several
 * outgoing edges leave at their left / right tip). Several edges at one side share it evenly (minimum 8 px apart on a
 * box wide enough, never the same point). Back edges leave at the right of the source, run up in the right-hand
 * corridor of the source lane and come back at the target; foreign lanes are crossed horizontally at the height of the
 * target row only. Everything else is delegated to the obstacle router.
 */
internal object ActOrthogonalRouter {
    private const val SIDE_MARGIN = 2f
    private const val CORNER = 0f
    private const val STUB = 4f
    private const val SIDE_SLACK = 4f
    private val DOWN = Point(x = 0f, y = 1f)
    private val UP = Point(x = 0f, y = -1f)
    private val LEFT = Point(x = -1f, y = 0f)
    private val RIGHT = Point(x = 1f, y = 0f)

    fun routeAll(
        nodes: Map<String, Rect>,
        kinds: Map<String, ActivityNodeKind>,
        edges: List<ActEdgeEnds>,
        backEdges: Set<String>,
        laneOfNode: Map<String, String>,
        corridorX: Map<String, Float>,
    ): Map<EdgeId, EdgeRoute> {
        val out = LinkedHashMap<EdgeId, EdgeRoute>()
        val live = edges.filter { it.source in nodes && it.target in nodes && it.source != it.target }

        fun cx(id: String) = nodes.getValue(id).let { it.origin.x + it.size.width / 2f }
        val forward = live.filter { it.id !in backEdges }
        val outBy = forward.groupBy { it.source }
        val inBy = forward.groupBy { it.target }

        // Sources: assign start point and direction per edge.
        val start = HashMap<String, Pair<Point, Point>>()
        for ((s, list) in outBy) {
            val r = nodes.getValue(s)
            val sorted = list.sortedWith(compareBy({ cx(it.target) }, { it.id }))
            val bottom = r.origin.y + r.size.height
            val diamond = kinds[s] == ActivityNodeKind.Decision || kinds[s] == ActivityNodeKind.Merge
            if (diamond && sorted.size >= 2) {
                val cy = r.origin.y + r.size.height / 2f
                val mid = sorted.drop(1).dropLast(1)
                start[sorted.first().id] = Point(x = r.origin.x, y = cy) to LEFT
                // Two outgoing edges use the left tip and the bottom, so the right side stays free for the node name.
                start[sorted.last().id] =
                    if (sorted.size ==
                        2
                    ) {
                        Point(x = r.origin.x + r.size.width / 2f, y = bottom) to DOWN
                    } else {
                        Point(x = r.origin.x + r.size.width, y = cy) to
                            RIGHT
                    }
                mid.forEachIndexed { i, e ->
                    start[e.id] = Point(x = spread(r = r, i = i, n = mid.size, wide = false), y = bottom) to DOWN
                }
            } else {
                sorted.forEachIndexed { i, e ->
                    start[e.id] =
                        Point(x = spread(r = r, i = i, n = sorted.size, wide = !diamond), y = bottom) to DOWN
                }
            }
        }
        val sideEntries =
            assignFinalSideEntries(nodes = nodes, kinds = kinds, inBy = inBy, start = start, cx = ::cx)
        val end = HashMap<String, Pair<Point, Point>>()
        for ((t, list) in inBy) {
            val r = nodes.getValue(t)
            val sorted = list.sortedWith(compareBy({ cx(it.source) }, { it.id })).filter { it.id !in sideEntries }
            sorted.forEachIndexed { i, e ->
                end[e.id] = Point(x = spread(r = r, i = i, n = sorted.size, wide = true), y = r.origin.y) to UP
            }
        }
        end.putAll(sideEntries)

        for (e in forward) {
            val (p0, d0) = start.getValue(e.id)
            val (p1, d1) = end.getValue(e.id)
            out[EdgeId(e.id)] = toRoute(routeEdge(nodes = nodes, e = e, p0 = p0, d0 = d0, p1 = p1, d1 = d1))
        }

        // Back edges: right side of the source, corridor of its lane, back into the target.
        val perLane = HashMap<String, Int>()
        for (e in live.filter { it.id in backEdges }) {
            val s = nodes.getValue(e.source)
            val t = nodes.getValue(e.target)
            val lane = laneOfNode[e.source]
            val idx = perLane[lane ?: ""] ?: 0
            perLane[lane ?: ""] = idx + 1
            val corridor = (lane?.let { corridorX[it] } ?: (s.origin.x + s.size.width + 12f)) - idx * 5f
            val sy = s.origin.y + s.size.height / 2f
            val ty = t.origin.y + t.size.height / 2f
            val enterRight = t.origin.x + t.size.width + 6f < corridor
            val tx = if (enterRight) t.origin.x + t.size.width else t.origin.x
            val pts =
                listOf(
                    Point(x = s.origin.x + s.size.width, y = sy),
                    Point(x = corridor, y = sy),
                    Point(x = corridor, y = ty),
                    Point(x = tx, y = ty),
                )
            val obstacles = nodes.filterKeys { it != e.source && it != e.target }.values.toList()
            val clean = IbdObstacleRouter.collisions(path = pts, obstacles = obstacles) == 0
            out[EdgeId(e.id)] =
                toRoute(
                    if (clean) {
                        pts
                    } else {
                        run {
                            val d = if (enterRight) RIGHT else LEFT
                            val a = Point(x = pts.first().x + STUB, y = pts.first().y)
                            val b = Point(x = pts.last().x + d.x * STUB, y = pts.last().y)
                            IbdObstacleRouter.simplifyPath(
                                listOf(pts.first()) +
                                    IbdObstacleRouter.route(from = a, fromDir = RIGHT, to = b, toDir = d, obstacles = obstacles) +
                                    listOf(pts.last()),
                            )
                        }
                    },
                )
        }
        return out
    }

    /** Obstacle-routed path of one forward edge between the given end points (with the short stubs outside the boxes). */
    private fun routeEdge(
        nodes: Map<String, Rect>,
        e: ActEdgeEnds,
        p0: Point,
        d0: Point,
        p1: Point,
        d1: Point,
    ): List<Point> {
        val obstacles = nodes.filterKeys { it != e.source && it != e.target }.values.toList()
        // The router keeps 2 px away from the two connected boxes, so it starts at a short stub outside them.
        val a = Point(x = p0.x + d0.x * STUB, y = p0.y + d0.y * STUB)
        val b = Point(x = p1.x + d1.x * STUB, y = p1.y + d1.y * STUB)
        val mid =
            IbdObstacleRouter.route(
                from = a,
                fromDir = d0,
                to = b,
                toDir = d1,
                obstacles = obstacles,
                endObstacles = listOf(nodes.getValue(e.source), nodes.getValue(e.target)),
            )
        return IbdObstacleRouter.simplifyPath(listOf(p0) + mid + listOf(p1))
    }

    /** (collisions with foreign boxes, bends); lexicographically smaller is better. */
    private fun routeScore(
        pts: List<Point>,
        obstacles: List<Rect>,
    ): Pair<Int, Int> = IbdObstacleRouter.collisions(path = pts, obstacles = obstacles) to maxOf(0, pts.size - 2)

    private fun isBetter(
        a: Pair<Int, Int>,
        b: Pair<Int, Int>,
    ): Boolean = a.first < b.first || (a.first == b.first && a.second < b.second)

    /**
     * Final nodes with two or more incoming forward flows: at most one flow per side enters the circle at its left or
     * right tip (centre height) instead of the top, so the arrowheads do not pile up on a 24 px circle. A side entry is
     * kept only if its route is strictly better (fewer collisions, then fewer bends) than the usual top entry.
     */
    private fun assignFinalSideEntries(
        nodes: Map<String, Rect>,
        kinds: Map<String, ActivityNodeKind>,
        inBy: Map<String, List<ActEdgeEnds>>,
        start: Map<String, Pair<Point, Point>>,
        cx: (String) -> Float,
    ): Map<String, Pair<Point, Point>> {
        val result = HashMap<String, Pair<Point, Point>>()
        for ((t, list) in inBy) {
            val kind = kinds[t]
            if ((kind != ActivityNodeKind.Final && kind != ActivityNodeKind.FlowFinal) || list.size < 2) continue
            val r = nodes.getValue(t)
            val cy = r.origin.y + r.size.height / 2f
            val left = r.origin.x
            val right = r.origin.x + r.size.width
            val topOrder = list.sortedWith(compareBy({ cx(it.source) }, { it.id }))
            for (leftSide in listOf(true, false)) {
                val candidates =
                    list.filter { e ->
                        val src = nodes.getValue(e.source)
                        val bottom = src.origin.y + src.size.height
                        val x = cx(e.source)
                        e.id !in result && bottom <= cy && (if (leftSide) x < left - SIDE_SLACK else x > right + SIDE_SLACK)
                    }
                val e =
                    candidates
                        .sortedWith(compareBy({ -(nodes.getValue(it.source).let { s -> s.origin.y + s.size.height }) }, { it.id }))
                        .firstOrNull() ?: continue
                val (p0, d0) = start.getValue(e.id)
                val obstacles = nodes.filterKeys { it != e.source && it != e.target }.values.toList()
                val sidePoint = Point(x = if (leftSide) left else right, y = cy)
                val sideRoute =
                    routeEdge(nodes = nodes, e = e, p0 = p0, d0 = d0, p1 = sidePoint, d1 = if (leftSide) LEFT else RIGHT)
                val topPoint = Point(x = spread(r = r, i = topOrder.indexOf(e), n = topOrder.size, wide = true), y = r.origin.y)
                val topRoute = routeEdge(nodes = nodes, e = e, p0 = p0, d0 = d0, p1 = topPoint, d1 = UP)
                if (isBetter(
                        a = routeScore(pts = sideRoute, obstacles = obstacles),
                        b = routeScore(pts = topRoute, obstacles = obstacles),
                    )
                ) {
                    result[e.id] = sidePoint to (if (leftSide) LEFT else RIGHT)
                }
            }
        }
        return result
    }

    private fun spread(
        r: Rect,
        i: Int,
        n: Int,
        wide: Boolean,
    ): Float {
        if (n <= 1) return r.origin.x + r.size.width / 2f
        return if (wide && r.size.width > 40f) {
            r.origin.x + r.size.width * (i + 1) / (n + 1)
        } else {
            val span = r.size.width - 2 * SIDE_MARGIN
            r.origin.x + SIDE_MARGIN + span * i / (n - 1)
        }
    }

    private fun toRoute(pts: List<Point>): EdgeRoute =
        if (pts.size <= 2) {
            EdgeRoute.Direct(source = pts.first(), target = pts.last())
        } else {
            EdgeRoute.OrthogonalRounded(
                source = pts.first(),
                target = pts.last(),
                waypoints = pts.subList(1, pts.size - 1),
                cornerRadiusPx = CORNER,
            )
        }
}
