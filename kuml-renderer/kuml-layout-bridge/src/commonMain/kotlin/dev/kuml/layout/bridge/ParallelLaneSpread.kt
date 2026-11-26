@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.LayoutGraph
import dev.kuml.layout.LayoutResult
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs

/** Distance (px) the two segments of a spread pair are brought to (the minimum is [EDGE_EDGE_TARGET_GAP_PX]). */
private const val SPREAD_TARGET_GAP_PX = 24f

/** A segment attached to a node may slide along the node's side but stays this far from its corners. */
private const val SPREAD_CORNER_MARGIN_PX = 3f

/** DoS guard: number of passes and pair fixes per layout. */
private const val SPREAD_MAX_PASSES = 3

/** Float safety margin so a spread pair ends above, not on, the 16 px limit. */
private const val SPREAD_EPS_PX = 0.5f
private const val SPREAD_MAX_FIXES = 64

/**
 * Moves the segments of parallel edge pairs that run closer than [EDGE_EDGE_TARGET_GAP_PX] apart, symmetrically around
 * their common middle, towards [SPREAD_TARGET_GAP_PX] (never below the minimum). An interior segment moves with its
 * two bends; an end segment slides along the side of the node it is attached to and stays inside that side. A fix is
 * kept only when the number of clearance violations of the whole layout goes down, so a move that pushes a segment
 * towards a node or frame is undone. A layout without a violation is returned unchanged. PAL wave J.
 */
internal fun LayoutResult.spreadParallelEdges(
    graph: LayoutGraph,
    pinnedEndSegments: Boolean = false,
    portAware: Boolean = false,
    extraObstacles: List<Rect> = emptyList(),
    bounds: Rect? = null,
    fixedEdges: Set<EdgeId> = emptySet(),
): LayoutResult {
    var current = this
    var fixes = 0
    for (pass in 0 until SPREAD_MAX_PASSES) {
        val violations = detectViolations(result = current, graph = graph, portAware = portAware)
        if (violations.parallel.isEmpty()) return current
        var progressed = false
        // sorted so that the outcome does not depend on the order of the graph's edges (PAL wave T)
        val ordered =
            if (pinnedEndSegments) {
                violations.parallel.sortedWith(
                    compareBy({ minOf(it.first.value, it.second.value) }, { maxOf(it.first.value, it.second.value) }),
                )
            } else {
                violations.parallel
            }
        for (v in ordered) {
            if (fixes >= SPREAD_MAX_FIXES) return current
            // with pinned ends the other push direction is tried when the first one adds a crossing or hits a square
            for (flip in if (pinnedEndSegments) listOf(false, true) else listOf(false)) {
                val candidate =
                    current.spreadPair(
                        graph = graph,
                        first = v.first,
                        second = v.second,
                        pinnedEndSegments = pinnedEndSegments,
                        fixedEdges = fixedEdges,
                        flip = flip,
                    ) ?: continue
                fixes++
                if (pinnedEndSegments &&
                    !current.acceptsLaneMove(
                        candidate = candidate,
                        ids = listOf(v.first, v.second),
                        obstacles = extraObstacles,
                        bounds = bounds,
                    )
                ) {
                    continue
                }
                val after = detectViolations(result = candidate, graph = graph, portAware = portAware).count
                if (after < detectViolations(result = current, graph = graph, portAware = portAware).count) {
                    current = candidate
                    progressed = true
                    break
                }
            }
        }
        if (!progressed) return current
    }
    return current
}

/**
 * IBD lane move guard (PAL wave T): the candidate must not add crossings, must not hit more port squares (or other
 * obstacles) than before and must keep an inner bend at least `min(12 px, before)` away from the frame.
 */
private fun LayoutResult.acceptsLaneMove(
    candidate: LayoutResult,
    ids: List<EdgeId>,
    obstacles: List<Rect>,
    bounds: Rect?,
): Boolean {
    fun routes(r: LayoutResult) = r.edges.values.mapNotNull { polylinePoints(it) }
    if (countCrossings(routes(candidate)) > countCrossings(routes(this))) return false

    fun hits(r: LayoutResult): Int =
        ids.sumOf { id ->
            val pts = r.edges[id]?.let { polylinePoints(it) } ?: return@sumOf 0
            pts.zipWithNext().sumOf { (a, b) -> obstacles.count { o -> segmentIntersectsRect(a = a, b = b, r = o) } }
        }
    if (hits(candidate) > hits(this)) return false
    if (bounds != null) {
        fun frameGap(r: LayoutResult): Float =
            ids
                .flatMap { id ->
                    val pts = r.edges[id]?.let { polylinePoints(it) } ?: return@flatMap emptyList<Point>()
                    if (pts.size > 2) pts.subList(1, pts.size - 1) else emptyList()
                }.minOfOrNull { p ->
                    minOf(
                        p.x - bounds.origin.x,
                        bounds.origin.x + bounds.size.width - p.x,
                        p.y - bounds.origin.y,
                        bounds.origin.y + bounds.size.height - p.y,
                    )
                } ?: Float.MAX_VALUE
        if (frameGap(candidate) < minOf(LANE_FRAME_MIN_GAP_PX, frameGap(this)) - 0.01f) return false
    }
    return true
}

/** An inner bend keeps this distance (px) from the owner frame, or the distance it had before. */
private const val LANE_FRAME_MIN_GAP_PX = 12f

/** A stub next to a moved inner segment never gets shorter than this (px), or its former length when that was shorter. */
private const val LANE_MIN_STUB_PX = 12f

/** Any other neighbour segment of a moved inner segment stays at least as long as a rounded corner needs. */
private const val LANE_MIN_NEIGHBOUR_PX = 4f

private fun LayoutResult.spreadPair(
    graph: LayoutGraph,
    first: EdgeId,
    second: EdgeId,
    pinnedEndSegments: Boolean = false,
    fixedEdges: Set<EdgeId> = emptySet(),
    flip: Boolean = false,
): LayoutResult? {
    val ra = edges[first] ?: return null
    val rb = edges[second] ?: return null
    val pa = polylinePoints(ra) ?: return null
    val pb = polylinePoints(rb) ?: return null
    // the closest pair of parallel segments with enough common length (with pinned ends: all pairs, closest first)
    val candidates = ArrayList<Triple<Int, Int, Float>>()
    for (i in 0 until pa.size - 1) {
        for (j in 0 until pb.size - 1) {
            val sa = pa[i] to pa[i + 1]
            val sb = pb[j] to pb[j + 1]
            val gap = parallelGap(sa, sb) ?: continue
            if (parallelOverlap(sa, sb) < PARALLEL_MIN_OVERLAP_PX || gap >= EDGE_EDGE_TARGET_GAP_PX) continue
            candidates += Triple(i, j, gap)
        }
    }
    val ordered =
        if (pinnedEndSegments) {
            candidates.sortedWith(compareBy({ it.third }, { it.first }, { it.second }))
        } else {
            candidates
                .fold<Triple<Int, Int, Float>, Triple<Int, Int, Float>?>(null) { best, c ->
                    if (best == null || c.third < best.third) c else best
                }?.let { listOf(it) } ?: emptyList()
        }
    val ea = graph.edges.firstOrNull { it.id == first } ?: return null
    val eb = graph.edges.firstOrNull { it.id == second } ?: return null
    for ((i, j, gap) in ordered) {
        val vertical = abs(pa[i].x - pa[i + 1].x) < 0.5f
        val coordA = if (vertical) pa[i].x else pa[i].y
        val coordB = if (vertical) pb[j].x else pb[j].y
        // an unequal pair is pushed apart, bundled segments (gap 0) apart in the order of the ids
        val dirA =
            (
                if (coordA == coordB) {
                    -1f
                } else if (coordA < coordB) {
                    -1f
                } else {
                    1f
                }
            ) * (if (flip) -1f else 1f)
        // first the target distance, then the minimum; both moving, then one of them alone (the other one may be pinned)
        for (targetGap in listOf(SPREAD_TARGET_GAP_PX, EDGE_EDGE_TARGET_GAP_PX)) {
            val need = targetGap - gap + SPREAD_EPS_PX
            val pin = pinnedEndSegments
            val freeA = first !in fixedEdges
            val freeB = second !in fixedEdges
            val moveA =
                if (freeA) {
                    pa.shifted(
                        index = i,
                        delta = dirA * need / 2f,
                        vertical = vertical,
                        graphEdge = ea,
                        result = this,
                        pinned = pin,
                    )
                } else {
                    null
                }
            val moveB =
                if (freeB) {
                    pb.shifted(
                        index = j,
                        delta = -dirA * need / 2f,
                        vertical = vertical,
                        graphEdge = eb,
                        result = this,
                        pinned = pin,
                    )
                } else {
                    null
                }
            if (moveA != null && moveB != null) return withRoutes(first to ra.withPoints(moveA), second to rb.withPoints(moveB))
            if (freeA) {
                pa
                    .shifted(index = i, delta = dirA * need, vertical = vertical, graphEdge = ea, result = this, pinned = pin)
                    ?.let { return withRoutes(first to ra.withPoints(it)) }
            }
            if (freeB) {
                pb
                    .shifted(index = j, delta = -dirA * need, vertical = vertical, graphEdge = eb, result = this, pinned = pin)
                    ?.let { return withRoutes(second to rb.withPoints(it)) }
            }
        }
    }
    return null
}

private fun List<Point>.shifted(
    index: Int,
    delta: Float,
    vertical: Boolean,
    graphEdge: dev.kuml.layout.LayoutEdge,
    result: LayoutResult,
    pinned: Boolean = false,
): List<Point>? {
    if (pinned) {
        // PAL wave T (IBD): ports are fixed anchors. An end segment never moves; an inner segment moves with its two bends and
        // the stubs next to it get longer or shorter, but keep their direction and a minimum length.
        if (index == 0 || index >= size - 2) return null
        val out = toMutableList()
        for (k in listOf(index, index + 1)) {
            val p = out[k]
            out[k] = if (vertical) Point(x = p.x + delta, y = p.y) else Point(x = p.x, y = p.y + delta)
        }
        for (k in listOf(index - 1, index + 1)) {
            val oldLen = abs(this[k + 1].x - this[k].x) + abs(this[k + 1].y - this[k].y)
            val dx0 = this[k + 1].x - this[k].x
            val dy0 = this[k + 1].y - this[k].y
            val dx1 = out[k + 1].x - out[k].x
            val dy1 = out[k + 1].y - out[k].y
            if (dx0 * dx1 + dy0 * dy1 <= 0f) return null
            val newLen = abs(dx1) + abs(dy1)
            val isStub = k == 0 || k == size - 2
            val floor = if (isStub) minOf(oldLen, LANE_MIN_STUB_PX) else minOf(oldLen, LANE_MIN_NEIGHBOUR_PX)
            if (newLen < floor - 0.01f) return null
        }
        return out
    }
    val out = toMutableList()
    for (k in listOf(index, index + 1)) {
        val p = out[k]
        val moved = if (vertical) Point(x = p.x + delta, y = p.y) else Point(x = p.x, y = p.y + delta)
        val attachedNode =
            when (k) {
                0 -> graphEdge.source.nodeId
                size - 1 -> graphEdge.target.nodeId
                else -> null
            }
        if (attachedNode != null) {
            val box = result.nodes[attachedNode]?.bounds ?: return null
            if (!slidesOnSide(box = box, moved = moved, vertical = vertical)) return null
        }
        out[k] = moved
    }
    return out
}

/** The moved end point still lies on the node's side (inside its extent, away from the corners). */
private fun slidesOnSide(
    box: Rect,
    moved: Point,
    vertical: Boolean,
): Boolean =
    if (vertical) {
        moved.x >= box.origin.x + SPREAD_CORNER_MARGIN_PX && moved.x <= box.origin.x + box.size.width - SPREAD_CORNER_MARGIN_PX
    } else {
        moved.y >= box.origin.y + SPREAD_CORNER_MARGIN_PX && moved.y <= box.origin.y + box.size.height - SPREAD_CORNER_MARGIN_PX
    }

internal fun EdgeRoute.withPoints(points: List<Point>): EdgeRoute =
    when (this) {
        is EdgeRoute.Direct -> copy(source = points.first(), target = points.last())
        is EdgeRoute.OrthogonalRounded -> copy(source = points.first(), target = points.last(), waypoints = points.drop(1).dropLast(1))
        else -> this
    }

internal fun LayoutResult.withRoutes(vararg changed: Pair<EdgeId, EdgeRoute>): LayoutResult = copy(edges = edges + changed)
