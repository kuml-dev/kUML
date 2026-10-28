package dev.kuml.io.svg.sysml2.edge

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Snaps the ends of transitions to the outline of junction circles and choice diamonds and fans
 * them out so neighbouring arrow heads are at least 30 degrees apart (less when more than twelve
 * meet), keeping arrow tips off each other and off the shape's centre.
 */
internal object StmPseudostateEndpoints {
    private const val MIN_GAP = (PI / 6.0).toFloat()

    /** [ends] lists (edge id, source id, target id); [circles] / [diamonds] are the shifted bounds by node id. */
    fun spread(
        routes: Map<String, EdgeRoute>,
        ends: List<Triple<String, String, String>>,
        circles: Map<String, Rect>,
        diamonds: Map<String, Rect>,
    ): Map<String, EdgeRoute> {
        val out = LinkedHashMap(routes)
        val shapes = circles.mapValues { it.value to true } + diamonds.mapValues { it.value to false }
        for ((nodeId, shapeInfo) in shapes.entries.sortedBy { it.key }.map { it.key to it.value }) {
            val (rect, isCircle) = shapeInfo
            val cx = rect.origin.x + rect.size.width / 2f
            val cy = rect.origin.y + rect.size.height / 2f
            // (edge id, isSource, neighbour point, natural angle)
            val attached = ArrayList<Triple<String, Boolean, Float>>()
            for ((edgeId, src, tgt) in ends.sortedBy { it.first }) {
                val r = out[edgeId] ?: continue
                if (tgt == nodeId) attached += Triple(edgeId, false, angle(cx = cx, cy = cy, p = neighbour(r = r, atTarget = true)))
                if (src == nodeId) attached += Triple(edgeId, true, angle(cx = cx, cy = cy, p = neighbour(r = r, atTarget = false)))
            }
            if (attached.isEmpty()) continue
            val sorted = attached.sortedBy { it.third }
            val gap = minOf(MIN_GAP, (2.0 * PI / sorted.size).toFloat())
            val angles = sorted.map { it.third }.toMutableList()
            for (i in 1 until angles.size) if (angles[i] - angles[i - 1] < gap) angles[i] = angles[i - 1] + gap
            sorted.forEachIndexed { i, (edgeId, isSource, _) ->
                val a = angles[i]
                val dx = cos(a)
                val dy = sin(a)
                val dist =
                    if (isCircle) {
                        minOf(rect.size.width, rect.size.height) / 2f + 1f
                    } else {
                        1f / max(abs(dx) / (rect.size.width / 2f) + abs(dy) / (rect.size.height / 2f), 0.0001f) + 1f
                    }
                val p = Point(x = cx + dx * dist, y = cy + dy * dist)
                out[edgeId] = withEnd(r = out.getValue(edgeId), isSource = isSource, p = p)
            }
        }
        return out
    }

    private fun angle(
        cx: Float,
        cy: Float,
        p: Point,
    ): Float = atan2(p.y - cy, p.x - cx)

    private fun neighbour(
        r: EdgeRoute,
        atTarget: Boolean,
    ): Point =
        when (r) {
            is EdgeRoute.Direct -> if (atTarget) r.source else r.target
            is EdgeRoute.OrthogonalRounded -> if (atTarget) r.waypoints.lastOrNull() ?: r.source else r.waypoints.firstOrNull() ?: r.target
            is EdgeRoute.TreeRounded -> if (atTarget) r.waypoints.lastOrNull() ?: r.source else r.waypoints.firstOrNull() ?: r.target
            is EdgeRoute.Bezier -> if (atTarget) r.controlPoints.lastOrNull() ?: r.source else r.controlPoints.firstOrNull() ?: r.target
        }

    private fun withEnd(
        r: EdgeRoute,
        isSource: Boolean,
        p: Point,
    ): EdgeRoute =
        when (r) {
            is EdgeRoute.Direct -> if (isSource) r.copy(source = p) else r.copy(target = p)
            is EdgeRoute.OrthogonalRounded -> if (isSource) r.copy(source = p) else r.copy(target = p)
            is EdgeRoute.TreeRounded -> if (isSource) r.copy(source = p) else r.copy(target = p)
            is EdgeRoute.Bezier -> if (isSource) r.copy(source = p) else r.copy(target = p)
        }
}
