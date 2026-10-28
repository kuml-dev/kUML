package dev.kuml.io.svg.sysml2

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.sysml2.PinSide
import kotlin.math.abs

/**
 * Deterministic orthogonalisation of edges that end at action pins (wave C2b). The layout engine
 * knows no pins, so after the end points were moved onto the pin squares the first / last segments
 * can be diagonal. This router replaces such a route by an axis-parallel one: a [STUB_PX] stub
 * perpendicular to the pin side, an L or Z bend in between, and a perpendicular entry into the pin.
 */
internal object Sysml2PinRouting {
    /** Length of the perpendicular stub at a pin, px. */
    const val STUB_PX: Float = 16f

    /** Corner radius used for the rebuilt route, px; small so the stub is never swallowed by a bend. */
    const val CORNER_RADIUS_PX: Float = 4f

    private const val TOL = 0.5f

    /** `true` when every segment of [route] is axis parallel (or the route is not a polyline). */
    fun isOrthogonal(route: EdgeRoute): Boolean {
        val pts = points(route) ?: return false
        return (0 until pts.size - 1).all { i ->
            abs(pts[i + 1].x - pts[i].x) <= TOL || abs(pts[i + 1].y - pts[i].y) <= TOL
        }
    }

    /**
     * Returns [route] unchanged when it is already orthogonal; otherwise an orthogonal route that
     * honours the pin sides. A `null` side means "no pin at this end": the direction is inferred
     * from the original first / last segment and no stub is added.
     */
    fun orthogonalize(
        route: EdgeRoute,
        sourceSide: PinSide?,
        targetSide: PinSide?,
    ): EdgeRoute {
        if (sourceSide == null && targetSide == null) return route
        if (isOrthogonal(route) &&
            lastSegmentOk(route = route, side = targetSide) &&
            firstSegmentOk(route = route, side = sourceSide)
        ) {
            return route
        }
        val p0 = route.source
        val pn = route.target
        val srcDir = sourceSide?.let { dir(it) } ?: inferDir(from = p0, toward = firstNeighbor(route))
        val tgtDir = targetSide?.let { dir(it) } ?: inferDir(from = pn, toward = lastNeighbor(route))
        val s = if (sourceSide != null) Point(x = p0.x + srcDir.x * STUB_PX, y = p0.y + srcDir.y * STUB_PX) else p0
        val t = if (targetSide != null) Point(x = pn.x + tgtDir.x * STUB_PX, y = pn.y + tgtDir.y * STUB_PX) else pn
        val srcVertical = abs(srcDir.y) > 0f
        val tgtVertical = abs(tgtDir.y) > 0f
        val mid = mutableListOf<Point>()
        when {
            srcVertical && tgtVertical -> {
                val m = (s.y + t.y) / 2f
                if ((m - s.y) * srcDir.y >= 0f && (m - t.y) * tgtDir.y >= 0f) {
                    mid += Point(x = s.x, y = m)
                    mid += Point(x = t.x, y = m)
                } else {
                    // The stubs overlap in flow direction: go around sideways (S shape).
                    val x = (s.x + t.x) / 2f
                    mid += Point(x = x, y = s.y)
                    mid += Point(x = x, y = t.y)
                }
            }
            !srcVertical && !tgtVertical -> {
                val m = (s.x + t.x) / 2f
                if ((m - s.x) * srcDir.x >= 0f && (m - t.x) * tgtDir.x >= 0f) {
                    mid += Point(x = m, y = s.y)
                    mid += Point(x = m, y = t.y)
                } else {
                    val y = (s.y + t.y) / 2f
                    mid += Point(x = s.x, y = y)
                    mid += Point(x = t.x, y = y)
                }
            }
            srcVertical -> mid += Point(x = s.x, y = t.y)
            else -> mid += Point(x = t.x, y = s.y)
        }
        val raw =
            buildList {
                if (sourceSide != null) add(s)
                addAll(mid)
                if (targetSide != null) add(t)
            }
        return EdgeRoute.OrthogonalRounded(
            source = p0,
            target = pn,
            waypoints = simplify(listOf(p0) + raw + listOf(pn)).drop(1).dropLast(1),
            cornerRadiusPx = CORNER_RADIUS_PX,
        )
    }

    /** Removes repeated and collinear interior points. */
    internal fun simplify(pts: List<Point>): List<Point> {
        val dedup = mutableListOf<Point>()
        for (p in pts) if (dedup.isEmpty() || abs(p.x - dedup.last().x) > 0.01f || abs(p.y - dedup.last().y) > 0.01f) dedup += p
        val out = mutableListOf<Point>()
        for (p in dedup) {
            while (out.size >= 2) {
                val a = out[out.size - 2]
                val b = out.last()
                val sameX = abs(a.x - b.x) <= TOL && abs(b.x - p.x) <= TOL && (b.y - a.y) * (p.y - b.y) > 0f
                val sameY = abs(a.y - b.y) <= TOL && abs(b.y - p.y) <= TOL && (b.x - a.x) * (p.x - b.x) > 0f
                val collinear = sameX || sameY
                if (collinear) out.removeAt(out.size - 1) else break
            }
            out += p
        }
        return out
    }

    private fun firstSegmentOk(
        route: EdgeRoute,
        side: PinSide?,
    ): Boolean {
        if (side == null) return true
        val pts = points(route) ?: return false
        return perpendicular(a = pts[0], b = pts[1], side = side)
    }

    private fun lastSegmentOk(
        route: EdgeRoute,
        side: PinSide?,
    ): Boolean {
        if (side == null) return true
        val pts = points(route) ?: return false
        return perpendicular(a = pts[pts.size - 1], b = pts[pts.size - 2], side = side)
    }

    /** `true` when the segment from the pin point [a] to [b] leaves outward, perpendicular to [side]. */
    private fun perpendicular(
        a: Point,
        b: Point,
        side: PinSide,
    ): Boolean {
        val d = dir(side)
        val dx = b.x - a.x
        val dy = b.y - a.y
        return if (abs(d.y) > 0f) abs(dx) <= TOL && dy * d.y > 0f else abs(dy) <= TOL && dx * d.x > 0f
    }

    private fun points(route: EdgeRoute): List<Point>? =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> null
        }

    private fun firstNeighbor(route: EdgeRoute): Point = points(route)?.getOrNull(1) ?: route.target

    private fun lastNeighbor(route: EdgeRoute): Point = points(route)?.let { it.getOrNull(it.size - 2) } ?: route.source

    private fun dir(side: PinSide): Point =
        when (side) {
            PinSide.Left -> Point(x = -1f, y = 0f)
            PinSide.Right -> Point(x = 1f, y = 0f)
            PinSide.Top -> Point(x = 0f, y = -1f)
            else -> Point(x = 0f, y = 1f)
        }

    private fun inferDir(
        from: Point,
        toward: Point,
    ): Point {
        val dx = toward.x - from.x
        val dy = toward.y - from.y
        return if (abs(dy) >= abs(dx)) Point(x = 0f, y = if (dy >= 0f) 1f else -1f) else Point(x = if (dx >= 0f) 1f else -1f, y = 0f)
    }
}
