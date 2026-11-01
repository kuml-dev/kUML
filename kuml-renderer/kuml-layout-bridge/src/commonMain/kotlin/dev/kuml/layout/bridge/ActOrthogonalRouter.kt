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
        val end = HashMap<String, Pair<Point, Point>>()
        for ((t, list) in inBy) {
            val r = nodes.getValue(t)
            val sorted = list.sortedWith(compareBy({ cx(it.source) }, { it.id }))
            sorted.forEachIndexed {
                i,
                e,
                ->
                end[e.id] = Point(x = spread(r = r, i = i, n = sorted.size, wide = true), y = r.origin.y) to UP
            }
        }

        for (e in forward) {
            val (p0, d0) = start.getValue(e.id)
            val (p1, d1) = end.getValue(e.id)
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
            val pts = IbdObstacleRouter.simplifyPath(listOf(p0) + mid + listOf(p1))
            out[EdgeId(e.id)] = toRoute(pts)
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
