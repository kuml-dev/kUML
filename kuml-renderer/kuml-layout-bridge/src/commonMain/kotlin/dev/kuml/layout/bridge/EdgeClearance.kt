package dev.kuml.layout.bridge

import dev.kuml.layout.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Target distance (px) between two parallel edges (PAL wave I). */
internal const val EDGE_EDGE_TARGET_GAP_PX = 16f

/** Smallest accepted distance (px) between two parallel edges that share a corridor. */
internal const val EDGE_EDGE_MIN_GAP_PX = 12f

/** Smallest accepted distance (px) between an edge segment and a node that is neither its source nor its target. */
internal const val EDGE_NODE_MIN_GAP_PX = 12f

/** Air (px) kept between a label box and a foreign edge segment. */
internal const val LABEL_CLEARANCE_PX = 4f

/** Offset (px) of a label from its own edge. */
internal const val LABEL_OWN_EDGE_OFFSET_PX = 4f

/** Minimum distance (px) between an initial-transition label and the arrowhead. */
internal const val INIT_LABEL_ARROWHEAD_GAP_PX = 16f

/** Upper bound of routes / segments examined by the geometry helpers (hostile-input cap). */
private const val MAX_ROUTES = 512
private const val MAX_POINTS_PER_ROUTE = 64

/**
 * Smallest distance between two parallel (axis-aligned) segments, or `null` when they are not parallel or their
 * projections on the common axis do not overlap.
 */
internal fun parallelGap(
    a: Pair<Point, Point>,
    b: Pair<Point, Point>,
): Float? {
    val aVertical = abs(a.first.x - a.second.x) < PARALLEL_EPS
    val aHorizontal = abs(a.first.y - a.second.y) < PARALLEL_EPS
    val bVertical = abs(b.first.x - b.second.x) < PARALLEL_EPS
    val bHorizontal = abs(b.first.y - b.second.y) < PARALLEL_EPS
    return when {
        aVertical && bVertical ->
            if (overlap(a0 = a.first.y, a1 = a.second.y, b0 = b.first.y, b1 = b.second.y)) abs(a.first.x - b.first.x) else null
        aHorizontal && bHorizontal ->
            if (overlap(a0 = a.first.x, a1 = a.second.x, b0 = b.first.x, b1 = b.second.x)) abs(a.first.y - b.first.y) else null
        else -> null
    }
}

private const val PARALLEL_EPS = 0.5f

private fun overlap(
    a0: Float,
    a1: Float,
    b0: Float,
    b1: Float,
): Boolean = min(max(a0, a1), max(b0, b1)) - max(min(a0, a1), min(b0, b1)) > PARALLEL_EPS

/**
 * Number of proper crossings between the polylines in [routes] (two different routes only; touching at a shared end
 * point and collinear overlap do not count). O(segments^2), capped.
 */
internal fun countCrossings(routes: Collection<List<Point>>): Int {
    val capped = routes.take(MAX_ROUTES).map { it.take(MAX_POINTS_PER_ROUTE) }
    var count = 0
    for (i in capped.indices) {
        for (j in i + 1 until capped.size) {
            for ((a, b) in capped[i].zipWithNext()) {
                for ((c, d) in capped[j].zipWithNext()) {
                    if (properlyCross(a = a, b = b, c = c, d = d)) count++
                }
            }
        }
    }
    return count
}

private fun orient(
    p: Point,
    q: Point,
    r: Point,
): Float = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)

private fun properlyCross(
    a: Point,
    b: Point,
    c: Point,
    d: Point,
): Boolean {
    val o1 = orient(p = a, q = b, r = c)
    val o2 = orient(p = a, q = b, r = d)
    val o3 = orient(p = c, q = d, r = a)
    val o4 = orient(p = c, q = d, r = b)
    return o1 * o2 < -CROSS_EPS && o3 * o4 < -CROSS_EPS
}

private const val CROSS_EPS = 0.01f
