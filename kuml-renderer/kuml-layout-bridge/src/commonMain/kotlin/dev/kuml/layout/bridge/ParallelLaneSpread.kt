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
internal fun LayoutResult.spreadParallelEdges(graph: LayoutGraph): LayoutResult {
    var current = this
    var fixes = 0
    for (pass in 0 until SPREAD_MAX_PASSES) {
        val violations = detectViolations(result = current, graph = graph)
        if (violations.parallel.isEmpty()) return current
        var progressed = false
        for (v in violations.parallel) {
            if (fixes >= SPREAD_MAX_FIXES) return current
            val candidate = current.spreadPair(graph = graph, first = v.first, second = v.second) ?: continue
            fixes++
            if (detectViolations(result = candidate, graph = graph).count < detectViolations(result = current, graph = graph).count) {
                current = candidate
                progressed = true
            }
        }
        if (!progressed) return current
    }
    return current
}

private fun LayoutResult.spreadPair(
    graph: LayoutGraph,
    first: EdgeId,
    second: EdgeId,
): LayoutResult? {
    val ra = edges[first] ?: return null
    val rb = edges[second] ?: return null
    val pa = polylinePoints(ra) ?: return null
    val pb = polylinePoints(rb) ?: return null
    // the closest pair of parallel segments with enough common length
    var best: Triple<Int, Int, Float>? = null
    for (i in 0 until pa.size - 1) {
        for (j in 0 until pb.size - 1) {
            val sa = pa[i] to pa[i + 1]
            val sb = pb[j] to pb[j + 1]
            val gap = parallelGap(sa, sb) ?: continue
            if (parallelOverlap(sa, sb) < PARALLEL_MIN_OVERLAP_PX || gap >= EDGE_EDGE_TARGET_GAP_PX) continue
            if (best == null || gap < best.third) best = Triple(i, j, gap)
        }
    }
    val (i, j, gap) = best ?: return null
    val vertical = abs(pa[i].x - pa[i + 1].x) < 0.5f
    val coordA = if (vertical) pa[i].x else pa[i].y
    val coordB = if (vertical) pb[j].x else pb[j].y
    // an unequal pair is pushed apart, bundled segments (gap 0) apart in the order of the ids
    val dirA =
        if (coordA == coordB) {
            -1f
        } else if (coordA < coordB) {
            -1f
        } else {
            1f
        }
    val ea = graph.edges.firstOrNull { it.id == first } ?: return null
    val eb = graph.edges.firstOrNull { it.id == second } ?: return null
    // first the target distance, then the minimum; both moving, then one of them alone (the other one may be pinned)
    for (targetGap in listOf(SPREAD_TARGET_GAP_PX, EDGE_EDGE_TARGET_GAP_PX)) {
        val need = targetGap - gap + SPREAD_EPS_PX
        val moveA = pa.shifted(index = i, delta = dirA * need / 2f, vertical = vertical, graphEdge = ea, result = this)
        val moveB = pb.shifted(index = j, delta = -dirA * need / 2f, vertical = vertical, graphEdge = eb, result = this)
        if (moveA != null && moveB != null) return withRoutes(first to ra.withPoints(moveA), second to rb.withPoints(moveB))
        pa
            .shifted(index = i, delta = dirA * need, vertical = vertical, graphEdge = ea, result = this)
            ?.let { return withRoutes(first to ra.withPoints(it)) }
        pb
            .shifted(index = j, delta = -dirA * need, vertical = vertical, graphEdge = eb, result = this)
            ?.let { return withRoutes(second to rb.withPoints(it)) }
    }
    return null
}

private fun List<Point>.shifted(
    index: Int,
    delta: Float,
    vertical: Boolean,
    graphEdge: dev.kuml.layout.LayoutEdge,
    result: LayoutResult,
): List<Point>? {
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
