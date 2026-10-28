package dev.kuml.io.svg.sysml2

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs

/** One edge end attached to a fork / join bar: [edgeId], the bar-side flag and the x of its neighbour point. */
internal data class BarAttachment(
    val edgeId: String,
    val atTarget: Boolean,
    val neighborX: Float,
)

/**
 * Spreads the edge ends that meet a fork / join bar evenly along it (wave C2b): `x_i = left + w * i / (n + 1)`,
 * separately for arriving and leaving edges, ordered by the x of the neighbouring route point so lines do
 * not cross. The vertical lead-in segment of each edge is shifted along with its end point.
 */
internal object Sysml2ForkJoinAnchors {
    /** Minimum distance between two attachment points on a bar, px. */
    const val MIN_GAP_PX: Float = 16f

    /** Bar width needed for [n] attachments on one side. */
    fun requiredWidth(n: Int): Float = (n + 1) * MIN_GAP_PX

    /** Assigned x per `(edgeId, atTarget)` for the attachments [arriving] / [leaving] on [bar]. */
    fun distribute(
        bar: Rect,
        arriving: List<BarAttachment>,
        leaving: List<BarAttachment>,
    ): Map<Pair<String, Boolean>, Float> {
        val out = LinkedHashMap<Pair<String, Boolean>, Float>()
        for (group in listOf(arriving, leaving)) {
            val n = group.size
            if (n == 0) continue
            val sorted = group.sortedWith(compareBy({ it.neighborX }, { it.edgeId }))
            sorted.forEachIndexed { i, a ->
                out[a.edgeId to a.atTarget] = bar.origin.x + bar.size.width * (i + 1) / (n + 1)
            }
        }
        return out
    }

    /** [route] with the end ([atTarget] or source) moved to [x]; a vertical first / last segment follows. */
    fun moveEnd(
        route: EdgeRoute,
        atTarget: Boolean,
        x: Float,
    ): EdgeRoute {
        val old = if (atTarget) route.target else route.source
        val moved = Point(x = x, y = old.y)
        val wps =
            when (route) {
                is EdgeRoute.OrthogonalRounded -> route.waypoints
                is EdgeRoute.TreeRounded -> route.waypoints
                else -> emptyList()
            }
        val newWps = wps.toMutableList()
        if (newWps.isNotEmpty()) {
            val idx = if (atTarget) newWps.lastIndex else 0
            if (abs(newWps[idx].x - old.x) < 0.5f) newWps[idx] = newWps[idx].copy(x = x)
        }
        val s = if (atTarget) route.source else moved
        val t = if (atTarget) moved else route.target
        return when (route) {
            is EdgeRoute.Direct -> route.copy(source = s, target = t)
            is EdgeRoute.OrthogonalRounded -> route.copy(source = s, target = t, waypoints = newWps)
            is EdgeRoute.TreeRounded -> route.copy(source = s, target = t, waypoints = newWps)
            is EdgeRoute.Bezier -> route.copy(source = s, target = t)
        }
    }

    /** Like [moveEnd] but moves the end along the y axis; a horizontal first / last segment follows. */
    fun moveEndY(
        route: EdgeRoute,
        atTarget: Boolean,
        y: Float,
    ): EdgeRoute {
        val old = if (atTarget) route.target else route.source
        val moved = Point(x = old.x, y = y)
        val newWps =
            when (route) {
                is EdgeRoute.OrthogonalRounded -> route.waypoints.toMutableList()
                is EdgeRoute.TreeRounded -> route.waypoints.toMutableList()
                else -> mutableListOf()
            }
        if (newWps.isNotEmpty()) {
            val idx = if (atTarget) newWps.lastIndex else 0
            if (abs(newWps[idx].y - old.y) < 0.5f) newWps[idx] = newWps[idx].copy(y = y)
        }
        val s = if (atTarget) route.source else moved
        val t = if (atTarget) moved else route.target
        return when (route) {
            is EdgeRoute.Direct -> route.copy(source = s, target = t)
            is EdgeRoute.OrthogonalRounded -> route.copy(source = s, target = t, waypoints = newWps)
            is EdgeRoute.TreeRounded -> route.copy(source = s, target = t, waypoints = newWps)
            is EdgeRoute.Bezier -> route.copy(source = s, target = t)
        }
    }

    /**
     * Moves the end of a plain (non-pin) edge away from pin squares on the same box side, so the arrow
     * and the pin label (which sits to the right / above the pin) do not collide. [pins] are absolute
     * pin centres with their side.
     */
    fun clearOfPins(
        route: EdgeRoute,
        atTarget: Boolean,
        bounds: Rect,
        pins: List<Pair<dev.kuml.sysml2.PinSide, Point>>,
    ): EdgeRoute {
        val end = if (atTarget) route.target else route.source
        val top = abs(end.y - bounds.origin.y) <= 1f
        val bottom = abs(end.y - (bounds.origin.y + bounds.size.height)) <= 1f
        val left = abs(end.x - bounds.origin.x) <= 1f
        val right = abs(end.x - (bounds.origin.x + bounds.size.width)) <= 1f
        var r = route
        for ((side, c) in pins) {
            val cur = if (atTarget) r.target else r.source
            val vertical = (side == dev.kuml.sysml2.PinSide.Top && top) || (side == dev.kuml.sysml2.PinSide.Bottom && bottom)
            val horizontal = (side == dev.kuml.sysml2.PinSide.Left && left) || (side == dev.kuml.sysml2.PinSide.Right && right)
            if (vertical && cur.x > c.x - PIN_BAND_BEFORE_PX && cur.x < c.x + PIN_BAND_AFTER_PX) {
                r = asOrthogonal(route = r, vertical = true)
                r = moveEnd(route = r, atTarget = atTarget, x = maxOf(bounds.origin.x + 12f, c.x - PIN_CLEARANCE_PX))
            } else if (horizontal && cur.y > c.y - PIN_CLEARANCE_PX - 4f && cur.y < c.y + 12f) {
                r = asOrthogonal(route = r, vertical = false)
                r = moveEndY(route = r, atTarget = atTarget, y = minOf(bounds.origin.y + bounds.size.height - 12f, c.y + PIN_CLEARANCE_PX))
            }
        }
        return r
    }

    /** A straight axis-parallel [Direct][EdgeRoute.Direct] route becomes a Z route so one end can move sideways. */
    private fun asOrthogonal(
        route: EdgeRoute,
        vertical: Boolean,
    ): EdgeRoute {
        if (route !is EdgeRoute.Direct) return route
        val a = route.source
        val b = route.target
        val wps =
            if (vertical) {
                val ym = (a.y + b.y) / 2f
                listOf(Point(x = a.x, y = ym), Point(x = b.x, y = ym))
            } else {
                val xm = (a.x + b.x) / 2f
                listOf(Point(x = xm, y = a.y), Point(x = xm, y = b.y))
            }
        return EdgeRoute.OrthogonalRounded(source = a, target = b, waypoints = wps, cornerRadiusPx = 4f)
    }

    private const val PIN_CLEARANCE_PX: Float = 22f
    private const val PIN_BAND_BEFORE_PX: Float = 12f
    private const val PIN_BAND_AFTER_PX: Float = 90f
}
