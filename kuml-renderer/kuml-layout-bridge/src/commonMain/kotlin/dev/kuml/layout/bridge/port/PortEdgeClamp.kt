@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.port

import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs

/** Side of a port rectangle an edge attaches to. The outward normal of the face points towards the edge. */
public enum class PortFace { Top, Bottom, Left, Right }

/** A port rectangle together with the face an edge attaches to. */
public data class PortAttachment(
    val rect: Rect,
    val face: PortFace,
)

/**
 * Central clamping of edge end points to port borders (PAL wave 2C, P-IBD-08).
 *
 * The layout engine reports the **centre** of a port. For drawing, an edge has to end on the **outer border** of
 * the port, perpendicular to the face, with a final stub that is at least as long as the arrowhead. All SysML 2
 * port/pin renderings (IBD ports, PAR pins) route through this function instead of each deriving its own end point.
 * Without an attachment a route is returned unchanged (byte-identical).
 */
public object PortEdgeClamp {
    /** Arrowhead length of the SVG renderer (`SvgInlineArrow.ARROW_LEN`); keep both in sync. */
    public const val ARROW_LEN_PX: Float = 12f

    /** Tolerance for "lies on the border", px. */
    public const val ON_BORDER_TOL_PX: Float = 0.01f

    private const val TOL = 0.5f

    /** Middle of the face edge of [att]. */
    public fun borderPoint(att: PortAttachment): Point {
        val r = att.rect
        val cx = r.origin.x + r.size.width / 2f
        val cy = r.origin.y + r.size.height / 2f
        return when (att.face) {
            PortFace.Top -> Point(x = cx, y = r.origin.y)
            PortFace.Bottom -> Point(x = cx, y = r.origin.y + r.size.height)
            PortFace.Left -> Point(x = r.origin.x, y = cy)
            PortFace.Right -> Point(x = r.origin.x + r.size.width, y = cy)
        }
    }

    /** Face of [rect] that looks towards [neighbor] along the given axis. */
    public fun faceTowards(
        rect: Rect,
        horizontalAxis: Boolean,
        neighbor: Point,
    ): PortFace {
        val cx = rect.origin.x + rect.size.width / 2f
        val cy = rect.origin.y + rect.size.height / 2f
        return if (horizontalAxis) {
            if (neighbor.x >= cx) PortFace.Right else PortFace.Left
        } else {
            if (neighbor.y >= cy) PortFace.Bottom else PortFace.Top
        }
    }

    /** Outward unit normal of a face. */
    public fun normal(face: PortFace): Point =
        when (face) {
            PortFace.Top -> Point(x = 0f, y = -1f)
            PortFace.Bottom -> Point(x = 0f, y = 1f)
            PortFace.Left -> Point(x = -1f, y = 0f)
            PortFace.Right -> Point(x = 1f, y = 0f)
        }

    /** Opposite face. */
    public fun opposite(face: PortFace): PortFace =
        when (face) {
            PortFace.Top -> PortFace.Bottom
            PortFace.Bottom -> PortFace.Top
            PortFace.Left -> PortFace.Right
            PortFace.Right -> PortFace.Left
        }

    /**
     * Clamps [route] so that its first point lies on [source] and its last point on [target] (each optional).
     * Orthogonal routes keep their inner way points where possible; otherwise an L/Z route with perpendicular
     * stubs of at least [minStubPx] is built.
     */
    public fun clamp(
        route: EdgeRoute,
        source: PortAttachment?,
        target: PortAttachment?,
        minStubPx: Float = ARROW_LEN_PX,
        cornerRadiusPx: Float = 4f,
    ): EdgeRoute {
        if (source == null && target == null) return route
        val pts = polyline(route) ?: return route
        val bs = source?.let { borderPoint(it) } ?: pts.first()
        val bt = target?.let { borderPoint(it) } ?: pts.last()
        val inner = pts.subList(1, pts.size - 1)
        val srcN = source?.let { normal(it.face) }
        val tgtN = target?.let { normal(it.face) }

        // 1. Keep the way points when the route is already valid with the clamped end points.
        val kept = simplify(listOf(bs) + inner + listOf(bt))
        if (isValid(kept, srcN, tgtN, minStubPx, source != null, target != null)) {
            return rebuild(route, kept, cornerRadiusPx)
        }

        // 1b. A first/last segment that already leaves along the normal but is too short: push its lane outwards.
        val shifted = shiftShortStubs(kept, if (source != null) srcN else null, if (target != null) tgtN else null, minStubPx)
        if (shifted != null && isValid(shifted, srcN, tgtN, minStubPx, source != null, target != null)) {
            return rebuild(route, shifted, cornerRadiusPx)
        }

        // 2. Insert stubs and bridge the way points with elbows.
        val seq = mutableListOf(bs)
        if (srcN != null) seq += Point(x = bs.x + srcN.x * minStubPx, y = bs.y + srcN.y * minStubPx)
        seq += inner
        if (tgtN != null) seq += Point(x = bt.x + tgtN.x * minStubPx, y = bt.y + tgtN.y * minStubPx)
        seq += bt
        val ortho = elbows(seq, srcN, tgtN)
        if (isValid(ortho, srcN, tgtN, minStubPx, source != null, target != null)) {
            return rebuild(route, ortho, cornerRadiusPx)
        }

        // 3. Full rebuild: stubs plus an L or Z connection (inner way points are dropped).
        val s = if (srcN != null) Point(x = bs.x + srcN.x * minStubPx, y = bs.y + srcN.y * minStubPx) else bs
        val t = if (tgtN != null) Point(x = bt.x + tgtN.x * minStubPx, y = bt.y + tgtN.y * minStubPx) else bt
        val sd = srcN ?: inferDir(from = bs, toward = t)
        val td = tgtN ?: inferDir(from = bt, toward = s)
        val mid = mutableListOf<Point>()
        val sV = abs(sd.y) > 0f
        val tV = abs(td.y) > 0f
        // Detour offset used when the mid line would coincide with a stub line (back-to-back faces on one row/column).
        val detour = 2f * minStubPx
        when {
            sV && tV -> {
                val m = (s.y + t.y) / 2f
                if ((m - s.y) * sd.y >= 0f && (m - t.y) * td.y >= 0f && abs(m - s.y) > TOL && abs(m - t.y) > TOL) {
                    mid += Point(x = s.x, y = m)
                    mid += Point(x = t.x, y = m)
                } else {
                    val x = if (abs(s.x - t.x) > TOL) (s.x + t.x) / 2f else s.x + detour
                    mid += Point(x = x, y = s.y)
                    mid += Point(x = x, y = t.y)
                }
            }
            !sV && !tV -> {
                val m = (s.x + t.x) / 2f
                if ((m - s.x) * sd.x >= 0f && (m - t.x) * td.x >= 0f && abs(m - s.x) > TOL && abs(m - t.x) > TOL) {
                    mid += Point(x = m, y = s.y)
                    mid += Point(x = m, y = t.y)
                } else {
                    val y = if (abs(s.y - t.y) > TOL) (s.y + t.y) / 2f else s.y + detour
                    mid += Point(x = s.x, y = y)
                    mid += Point(x = t.x, y = y)
                }
            }
            sV -> {
                // Vertical source stub, horizontal target stub: the elbow (s.x, t.y) must not lie behind either stub.
                val corner = Point(x = s.x, y = t.y)
                if (continuesAlong(from = s, to = corner, dir = sd) && approachesAlong(from = corner, to = t, dir = td)) {
                    mid += corner
                } else {
                    val ay = s.y + sd.y * detour
                    mid += Point(x = s.x, y = ay)
                    mid += Point(x = t.x, y = ay)
                }
            }
            else -> {
                // Horizontal source stub, vertical target stub: the elbow (t.x, s.y) must not lie behind either stub.
                val corner = Point(x = t.x, y = s.y)
                if (continuesAlong(from = s, to = corner, dir = sd) && approachesAlong(from = corner, to = t, dir = td)) {
                    mid += corner
                } else {
                    val ax = s.x + sd.x * detour
                    mid += Point(x = ax, y = s.y)
                    mid += Point(x = ax, y = t.y)
                }
            }
        }
        val full =
            buildList {
                add(bs)
                if (srcN != null) add(s)
                addAll(mid)
                if (tgtN != null) add(t)
                add(bt)
            }
        return rebuild(route, simplify(full), cornerRadiusPx)
    }

    /**
     * Lengthens a too short end segment that already runs along the face normal by moving the adjoining perpendicular
     * lane outwards (both its end points), so routed way points around obstacles are kept. Returns null if not applicable.
     */
    private fun shiftShortStubs(
        pts: List<Point>,
        srcN: Point?,
        tgtN: Point?,
        minStub: Float,
    ): List<Point>? {
        fun shiftHead(
            list: List<Point>,
            n: Point?,
        ): List<Point> {
            if (n == null || list.size < 4) return list
            val a = list[0]
            val b = list[1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val along = dx * n.x + dy * n.y
            val perp = abs(dx * n.y - dy * n.x)
            if (perp > TOL || along <= TOL || along >= minStub - TOL) return list
            val d = minStub - along
            val out = list.toMutableList()
            out[1] = Point(x = b.x + n.x * d, y = b.y + n.y * d)
            out[2] = Point(x = list[2].x + n.x * d, y = list[2].y + n.y * d)
            return out
        }
        val head = shiftHead(pts, srcN)
        val result = shiftHead(head.reversed(), tgtN).reversed()
        return if (result == pts) null else simplify(result)
    }

    /** The move [from] -> [to] does not run against the outward direction [dir] of the stub that ends at [from]. */
    private fun continuesAlong(
        from: Point,
        to: Point,
        dir: Point,
    ): Boolean = (to.x - from.x) * dir.x + (to.y - from.y) * dir.y >= -TOL

    /** The move [from] -> [to] runs in the inward direction (-[dir]) of the target stub that starts at [to]. */
    private fun approachesAlong(
        from: Point,
        to: Point,
        dir: Point,
    ): Boolean = (to.x - from.x) * dir.x + (to.y - from.y) * dir.y <= TOL

    private fun rebuild(
        original: EdgeRoute,
        pts: List<Point>,
        cornerRadiusPx: Float,
    ): EdgeRoute {
        val wp = pts.drop(1).dropLast(1)
        return when {
            original is EdgeRoute.OrthogonalRounded ->
                original.copy(source = pts.first(), target = pts.last(), waypoints = wp)
            original is EdgeRoute.TreeRounded ->
                original.copy(source = pts.first(), target = pts.last(), waypoints = wp)
            else ->
                EdgeRoute.OrthogonalRounded(source = pts.first(), target = pts.last(), waypoints = wp, cornerRadiusPx = cornerRadiusPx)
        }
    }

    private fun polyline(route: EdgeRoute): List<Point>? =
        when (route) {
            is EdgeRoute.Direct -> listOf(route.source, route.target)
            is EdgeRoute.OrthogonalRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.TreeRounded -> listOf(route.source) + route.waypoints + listOf(route.target)
            is EdgeRoute.Bezier -> null
        }

    private fun isValid(
        pts: List<Point>,
        srcN: Point?,
        tgtN: Point?,
        minStub: Float,
        hasSrc: Boolean,
        hasTgt: Boolean,
    ): Boolean {
        if (pts.size < 2) return false
        for (i in 0 until pts.size - 1) {
            if (abs(pts[i + 1].x - pts[i].x) > TOL && abs(pts[i + 1].y - pts[i].y) > TOL) return false
        }
        if (hasBacktrack(pts)) return false
        if (hasSrc && srcN != null) {
            val a = pts[0]
            val b = pts[1]
            if (!leavesAlong(a, b, srcN, minStub)) return false
        }
        if (hasTgt && tgtN != null) {
            val a = pts[pts.size - 1]
            val b = pts[pts.size - 2]
            if (!leavesAlong(a, b, tgtN, minStub)) return false
        }
        return true
    }

    /** True when two consecutive collinear segments run in opposite directions (the line folds back on itself). */
    private fun hasBacktrack(pts: List<Point>): Boolean {
        for (i in 0 until pts.size - 2) {
            val a = pts[i]
            val b = pts[i + 1]
            val c = pts[i + 2]
            val vertical = abs(a.x - b.x) <= TOL && abs(b.x - c.x) <= TOL
            val horizontal = abs(a.y - b.y) <= TOL && abs(b.y - c.y) <= TOL
            if (vertical && (b.y - a.y) * (c.y - b.y) < 0f) return true
            if (horizontal && (b.x - a.x) * (c.x - b.x) < 0f) return true
        }
        return false
    }

    private fun leavesAlong(
        a: Point,
        b: Point,
        n: Point,
        minStub: Float,
    ): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return if (abs(n.y) > 0f) {
            abs(dx) <= TOL && dy * n.y >= minStub - TOL
        } else {
            abs(dy) <= TOL && dx * n.x >= minStub - TOL
        }
    }

    /** Inserts an elbow wherever two consecutive points differ in both coordinates. */
    private fun elbows(
        seq: List<Point>,
        srcN: Point?,
        tgtN: Point?,
    ): List<Point> {
        val out = mutableListOf(seq.first())
        // Direction (vertical?) of the previous segment, seeded with the source stub direction.
        var prevVertical: Boolean? = srcN?.let { abs(it.y) > 0f }
        for (i in 1 until seq.size) {
            val a = out.last()
            val b = seq[i]
            val diffX = abs(b.x - a.x) > TOL
            val diffY = abs(b.y - a.y) > TOL
            if (diffX && diffY) {
                val last = i == seq.size - 1
                // The final approach must run along the target normal.
                val verticalFirst =
                    if (last && tgtN != null) {
                        abs(tgtN.y) <= 0f
                    } else {
                        prevVertical == false
                    }
                out += if (verticalFirst) Point(x = a.x, y = b.y) else Point(x = b.x, y = a.y)
            }
            val cur = out.last()
            prevVertical =
                if (abs(b.x - cur.x) <= TOL &&
                    abs(b.y - cur.y) > TOL
                ) {
                    true
                } else if (abs(b.y - cur.y) <= TOL && abs(b.x - cur.x) > TOL) {
                    false
                } else {
                    prevVertical
                }
            out += b
        }
        return simplify(out)
    }

    private fun inferDir(
        from: Point,
        toward: Point,
    ): Point {
        val dx = toward.x - from.x
        val dy = toward.y - from.y
        return if (abs(dy) >= abs(dx)) Point(x = 0f, y = if (dy >= 0f) 1f else -1f) else Point(x = if (dx >= 0f) 1f else -1f, y = 0f)
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
                if (sameX || sameY) out.removeAt(out.size - 1) else break
            }
            out += p
        }
        return out
    }
}
