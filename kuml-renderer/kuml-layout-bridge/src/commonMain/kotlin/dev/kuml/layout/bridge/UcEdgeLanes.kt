package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.NodeId
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Post-routing passes of the use case grid (Nacharbeit 42). The grid routes every edge on its own, so after routing
 *
 *  - neighbouring edges share a vertical (horizontal) corridor and run on top of each other ([separate]),
 *  - a port coordinate and the track the router picked can differ by a few px, which draws a micro jog that the
 *    rounded corners turn into a bump ([collapseJogs]),
 *  - line ends sit on the bounding box of a use case instead of on its ellipse ([clipToEllipses]).
 *
 * The passes are pure functions of the routes and obstacles; ties are broken by edge id, so the result does not depend
 * on iteration order. The first and the last segment of a wire stay on their port and are never moved. This is a
 * planning pass for use case diagrams only; it is not shared with the parametric lane planner (`ParLanePlan`), whose
 * conventions it follows (8 px minimum pitch, 12 px preferred, id tie break).
 */
internal object UcEdgeLanes {
    const val MIN_GAP_PX = 8f
    const val PREF_GAP_PX = 12f

    /** Preferred pitch of corridors that lead to an actor; 12 and 8 px stay as fallbacks, an unmoved overlap is worse (Nacharbeit 54). */
    const val ACTOR_PITCH_PX = 14f

    /** A jog shorter than this (twice the corner radius) cannot be drawn as two clean corners. */
    const val JOG_MAX_PX = 16f

    /** Above this many segments the O(n^2) passes are skipped (the routes stay as routed). */
    const val MAX_SEGMENTS = 2000
    private const val MAX_PASSES = 3
    private const val MIN_END_PX = 6f
    private const val FRAME_BAND_PX = 4f
    private const val EPS = 0.01f
    private const val OVERLAP_MIN_PX = 1f
    private const val ELLIPSE_GRAZE = 0.98f

    /** A wire: the route of one edge as a mutable polyline plus the boxes of its own end nodes. */
    class Wire(
        val id: EdgeId,
        var pts: MutableList<Point>,
        var radius: Float,
        val sourceRect: Rect?,
        val targetRect: Rect?,
        /** True when one end of the wire is an actor box (Nacharbeit 54): its corridors keep the actor pitch. */
        val actorEnd: Boolean = false,
        /** Which end is the actor box (the other end is a use case ellipse whose side the line may slide along). */
        val sourceActor: Boolean = false,
        val targetActor: Boolean = false,
    ) {
        fun toRoute(): EdgeRoute.OrthogonalRounded =
            EdgeRoute.OrthogonalRounded(
                source = pts.first(),
                target = pts.last(),
                waypoints = pts.subList(1, pts.size - 1).toList(),
                cornerRadiusPx = radius,
            )
    }

    /** What the passes did, for tests and the work log. */
    data class Stats(
        val separated: Int = 0,
        val collapsed: Int = 0,
        val unresolved: Int = 0,
        val skipped: Boolean = false,
    )

    class Result(
        val routes: Map<EdgeId, EdgeRoute>,
        val stats: Stats,
    )

    /**
     * Runs [collapseJogs], then [separate]. [ends] gives the node ids of both ends of every edge, [nodes] all node boxes.
     */
    fun plan(
        routes: Map<EdgeId, EdgeRoute>,
        ends: Map<EdgeId, Pair<NodeId, NodeId>>,
        nodes: Map<NodeId, Rect>,
        frames: List<Rect> = emptyList(),
        actorIds: Set<NodeId> = emptySet(),
    ): Result {
        val wires = wiresOf(routes = routes, ends = ends, nodes = nodes, actorIds = actorIds)
        val total = wires.sumOf { it.pts.size - 1 }
        if (total > MAX_SEGMENTS) return Result(routes = routes, stats = Stats(skipped = true))
        val allRects = nodes.values.toList()
        val collapsed = collapseJogs(wires = wires, rects = allRects, frames = frames)
        val (separated, unresolved) = separate(wires = wires, rects = allRects, frames = frames)
        val out = LinkedHashMap<EdgeId, EdgeRoute>()
        val byId = wires.associateBy { it.id }
        for ((id, r) in routes) out[id] = byId[id]?.toRoute() ?: r
        return Result(routes = out, stats = Stats(separated = separated, collapsed = collapsed, unresolved = unresolved))
    }

    fun wiresOf(
        routes: Map<EdgeId, EdgeRoute>,
        ends: Map<EdgeId, Pair<NodeId, NodeId>>,
        nodes: Map<NodeId, Rect>,
        actorIds: Set<NodeId> = emptySet(),
    ): List<Wire> {
        val out = ArrayList<Wire>()
        for ((id, r) in routes) {
            if (r !is EdgeRoute.OrthogonalRounded) continue
            val pts = ArrayList<Point>()
            pts += r.source
            pts += r.waypoints
            pts += r.target
            val e = ends[id]
            out +=
                Wire(
                    id = id,
                    pts = simplify(pts),
                    radius = r.cornerRadiusPx,
                    sourceRect =
                        e?.let {
                            nodes[it.first]
                        },
                    targetRect = e?.let { nodes[it.second] },
                    actorEnd = e != null && (e.first in actorIds || e.second in actorIds),
                    sourceActor = e != null && e.first in actorIds,
                    targetActor = e != null && e.second in actorIds,
                )
        }
        return out
    }

    // ------------------------------------------------------------------ jogs

    /** Removes jogs shorter than [JOG_MAX_PX]; a jog that cannot be moved away gets sharp corners. Returns the number collapsed. */
    fun collapseJogs(
        wires: List<Wire>,
        rects: List<Rect>,
        frames: List<Rect> = emptyList(),
    ): Int {
        var collapsed = 0
        for (w in wires.sortedBy { it.id.value }) {
            var guard = 0
            var progress = true
            while (progress && guard++ < 16) {
                progress = false
                val n = w.pts.size
                // segments s-1 (A), s (B, the jog), s+1 (C)
                for (s in 1..(n - 3)) {
                    val len = segLen(a = w.pts[s], b = w.pts[s + 1])
                    if (len < EPS || len >= JOG_MAX_PX) continue
                    val a = s - 1
                    val c = s + 1
                    // move A onto C's line, else C onto A's line
                    val cCoord = coordOf(a = w.pts[c], b = w.pts[c + 1])
                    val aCoord = coordOf(a = w.pts[a], b = w.pts[a + 1])
                    val moved =
                        (a > 0 && tryMove(w = w, s = a, coord = cCoord, rects = rects, allowCollapse = true, frames = frames)) ||
                            (c < n - 2 && tryMove(w = w, s = c, coord = aCoord, rects = rects, allowCollapse = true, frames = frames))
                    if (moved) {
                        w.pts = simplify(w.pts)
                        collapsed++
                        progress = true
                        break
                    }
                }
            }
            // jogs that stay: no rounded corners (a bump is worse than a sharp bend)
            val n = w.pts.size
            if ((1..(n - 3)).any { s -> segLen(a = w.pts[s], b = w.pts[s + 1]).let { it > EPS && it < JOG_MAX_PX } }) w.radius = 0f
        }
        return collapsed
    }

    // ------------------------------------------------------------- corridors

    private class Seg(
        val wire: Int,
        val index: Int,
        val horizontal: Boolean,
        val coord: Float,
        val lo: Float,
        val hi: Float,
        val movable: Boolean,
    )

    /** Separates overlapping parallel segments of different wires. Returns (segments moved, conflicts left). */
    fun separate(
        wires: List<Wire>,
        rects: List<Rect>,
        frames: List<Rect> = emptyList(),
    ): Pair<Int, Int> {
        var moved = 0
        var left = 0
        for (pass in 0 until MAX_PASSES) {
            val segs = segmentsOf(wires)
            val groups = conflictGroups(segs)
            if (groups.isEmpty()) {
                left = 0
                break
            }
            var progressed = false
            left = 0
            for (g in groups) {
                val members =
                    g.filter { it.movable }.sortedWith(
                        compareBy({ crossingKey(w = wires[it.wire], s = it) }, { wires[it.wire].id.value }),
                    )
                val fixed = g.filter { !it.movable }
                if (members.isEmpty()) {
                    left++
                    continue
                }
                val base = g.map { it.coord }.average().toFloat()
                var done = false
                val pitches =
                    if (g.any { wires[it.wire].actorEnd }) {
                        listOf(
                            ACTOR_PITCH_PX,
                            PREF_GAP_PX,
                            MIN_GAP_PX,
                        )
                    } else {
                        listOf(PREF_GAP_PX, MIN_GAP_PX)
                    }
                for (pitch in pitches) {
                    val slots =
                        assignSlots(
                            wires = wires,
                            members = members,
                            fixed = fixed,
                            base = base,
                            pitch = pitch,
                            rects = rects,
                            frames = frames,
                        )
                            ?: continue
                    val snapshot = members.map { wires[it.wire].pts.toList() }
                    var ok = true
                    for ((j, m) in members.withIndex()) {
                        if (abs(m.coord - slots[j]) < EPS) continue
                        if (!tryMove(w = wires[m.wire], s = m.index, coord = slots[j], rects = rects, frames = frames)) {
                            ok = false
                            break
                        }
                    }
                    if (ok) {
                        moved += members.size
                        progressed = true
                        done = true
                        break
                    }
                    members.forEachIndexed { j, m -> wires[m.wire].pts = snapshot[j].toMutableList() }
                }
                if (!done) left++
            }
            for (w in wires) w.pts = simplify(w.pts)
            if (!progressed) break
        }
        nudgeEnds(wires = wires, rects = rects, frames = frames)
        // final count of what is still in conflict
        left = conflictGroups(segmentsOf(wires)).size
        return moved to left
    }

    /**
     * Last resort for a conflict between segments that are all fixed (the end segments stay on their ports): the end
     * segment at a use case slides along the ellipse side to a coordinate [MIN_GAP_PX] away from the other segments, as
     * long as it stays 12 px inside the vertical (horizontal) extent of the use case (Nacharbeit 54: a taller actor box
     * moves the actor ports, so two end segments can end up on almost the same coordinate).
     */
    private fun nudgeEnds(
        wires: List<Wire>,
        rects: List<Rect>,
        frames: List<Rect>,
    ) {
        val groups = conflictGroups(segmentsOf(wires))
        for (g in groups) {
            for (seg in g.sortedBy { wires[it.wire].id.value }) {
                val w = wires[seg.wire]
                val n = w.pts.size
                val atSource = seg.index == 0
                val atTarget = seg.index == n - 2
                if (!atSource && !atTarget) continue
                if ((atSource && w.sourceActor) || (atTarget && w.targetActor)) continue
                val box = (if (atSource) w.sourceRect else w.targetRect) ?: continue
                val lo = (if (seg.horizontal) box.origin.y else box.origin.x) + 12f
                val hi = (if (seg.horizontal) box.origin.y + box.size.height else box.origin.x + box.size.width) - 12f
                val others = g.filter { it.wire != seg.wire && it.horizontal == seg.horizontal }
                val before = conflictGroups(segmentsOf(wires)).size
                val tried =
                    others
                        .flatMap { listOf(it.coord - MIN_GAP_PX - 0.5f, it.coord + MIN_GAP_PX + 0.5f) }
                        .filter { it in lo..hi }
                        .sortedBy { abs(it - seg.coord) }
                var fixed = false
                for (c in tried) {
                    val snapshot = w.pts.toList()
                    if (tryMove(w = w, s = seg.index, coord = c, rects = rects, frames = frames) &&
                        conflictGroups(segmentsOf(wires)).size < before
                    ) {
                        fixed = true
                        break
                    }
                    w.pts = snapshot.toMutableList()
                }
                if (fixed) break
            }
        }
    }

    /**
     * One coordinate per member (members are in crossing order): strictly increasing, [pitch] apart, none within
     * [pitch] of a fixed segment, each valid for its member (obstacles, end stubs), minimal total distance from
     * [base]. `null` when no assignment exists.
     */
    private fun assignSlots(
        wires: List<Wire>,
        members: List<Seg>,
        fixed: List<Seg>,
        base: Float,
        pitch: Float,
        rects: List<Rect>,
        frames: List<Rect>,
    ): List<Float>? {
        val m = members.size
        val span = m + fixed.size + 2
        val half = if (m % 2 == 0) 0.5f else 0f
        val cands = (-span..span).map { base + (it + half) * pitch }.filter { c -> fixed.none { abs(it.coord - c) < pitch - EPS } }
        if (cands.size < m) return null
        val inf = Float.MAX_VALUE / 4f
        val cost =
            Array(m) { j ->
                FloatArray(cands.size) { k ->
                    if (canMove(w = wires[members[j].wire], s = members[j].index, coord = cands[k], rects = rects, frames = frames)) {
                        abs(
                            cands[k] - base,
                        )
                    } else {
                        inf
                    }
                }
            }
        val dp = Array(m) { FloatArray(cands.size) { inf } }
        val from = Array(m) { IntArray(cands.size) { -1 } }
        for (k in cands.indices) dp[0][k] = cost[0][k]
        for (j in 1 until m) {
            for (k in cands.indices) {
                if (cost[j][k] >= inf) continue
                var best = inf
                var arg = -1
                for (kp in 0 until k) {
                    if (dp[j - 1][kp] < best) {
                        best = dp[j - 1][kp]
                        arg = kp
                    }
                }
                if (arg >= 0) {
                    dp[j][k] = best + cost[j][k]
                    from[j][k] = arg
                }
            }
        }
        var end = -1
        var endCost = inf
        for (k in cands.indices) {
            if (dp[m - 1][k] < endCost) {
                endCost = dp[m - 1][k]
                end = k
            }
        }
        if (end < 0) return null
        val out = FloatArray(m)
        var k = end
        for (j in m - 1 downTo 0) {
            out[j] = cands[k]
            k = from[j][k]
        }
        return out.toList()
    }

    private fun canMove(
        w: Wire,
        s: Int,
        coord: Float,
        rects: List<Rect>,
        frames: List<Rect>,
    ): Boolean {
        val probe =
            Wire(
                id = w.id,
                pts = w.pts.toMutableList(),
                radius = w.radius,
                sourceRect = w.sourceRect,
                targetRect = w.targetRect,
                actorEnd = w.actorEnd,
                sourceActor = w.sourceActor,
                targetActor = w.targetActor,
            )
        return tryMove(w = probe, s = s, coord = coord, rects = rects, frames = frames)
    }

    /** Smaller key = placed at the smaller coordinate: segments whose neighbours lead to the small side go there. */
    private fun crossingKey(
        w: Wire,
        s: Seg,
    ): Int {
        val p = w.pts
        val i = s.index

        fun sign(v: Float): Int =
            if (v < -EPS) {
                -1
            } else if (v > EPS) {
                1
            } else {
                0
            }
        val before = if (s.horizontal) sign(p[i - 1].y - p[i].y) else sign(p[i - 1].x - p[i].x)
        val after = if (s.horizontal) sign(p[i + 2].y - p[i + 1].y) else sign(p[i + 2].x - p[i + 1].x)
        return before + after
    }

    private fun segmentsOf(wires: List<Wire>): List<Seg> {
        val out = ArrayList<Seg>()
        for ((wi, w) in wires.withIndex()) {
            val n = w.pts.size
            for (s in 0 until n - 1) {
                val a = w.pts[s]
                val b = w.pts[s + 1]
                val horizontal = abs(a.y - b.y) < EPS
                val vertical = abs(a.x - b.x) < EPS
                if (horizontal == vertical) continue // degenerate or diagonal
                out +=
                    Seg(
                        wire = wi,
                        index = s,
                        horizontal = horizontal,
                        coord = if (horizontal) a.y else a.x,
                        lo = if (horizontal) min(a.x, b.x) else min(a.y, b.y),
                        hi = if (horizontal) max(a.x, b.x) else max(a.y, b.y),
                        movable = s in 1..(n - 3),
                    )
            }
        }
        return out
    }

    private fun conflicts(
        a: Seg,
        b: Seg,
    ): Boolean =
        a.wire != b.wire &&
            a.horizontal == b.horizontal &&
            abs(a.coord - b.coord) < MIN_GAP_PX - EPS &&
            min(a.hi, b.hi) - max(a.lo, b.lo) > OVERLAP_MIN_PX

    /** Connected groups of mutually conflicting segments that contain at least two wires. */
    private fun conflictGroups(segs: List<Seg>): List<List<Seg>> {
        val parent = IntArray(segs.size) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) {
                val nx = parent[c]
                parent[c] = r
                c = nx
            }
            return r
        }
        val inConflict = BooleanArray(segs.size)
        for (i in segs.indices) {
            for (j in i + 1 until segs.size) {
                if (conflicts(a = segs[i], b = segs[j])) {
                    inConflict[i] = true
                    inConflict[j] = true
                    parent[find(i)] = find(j)
                }
            }
        }
        val groups = LinkedHashMap<Int, MutableList<Seg>>()
        for (i in segs.indices) if (inConflict[i]) groups.getOrPut(find(i)) { ArrayList() } += segs[i]
        return groups.values.toList()
    }

    // ------------------------------------------------------------ moving one

    /** Moves segment [s] of [w] to [coord] (perpendicular to its axis) if the result is valid; the wire is unchanged otherwise. */
    private fun tryMove(
        w: Wire,
        s: Int,
        coord: Float,
        rects: List<Rect>,
        allowCollapse: Boolean = false,
        frames: List<Rect> = emptyList(),
    ): Boolean {
        val old = w.pts
        val a = old[s]
        val b = old[s + 1]
        val horizontal = abs(a.y - b.y) < EPS
        val vertical = abs(a.x - b.x) < EPS
        if (horizontal == vertical) return false
        if (violatesFrame(horizontal = horizontal, a = a, b = b, newCoord = coord, frames = frames)) return false
        val next = old.toMutableList()
        if (horizontal) {
            next[s] = Point(x = a.x, y = coord)
            next[s + 1] = Point(x = b.x, y = coord)
        } else {
            next[s] = Point(x = coord, y = a.y)
            next[s + 1] = Point(x = coord, y = b.y)
        }
        val last = old.size - 2
        for (k in max(0, s - 1)..min(last, s + 1)) {
            val o0 = old[k]
            val o1 = old[k + 1]
            val n0 = next[k]
            val n1 = next[k + 1]
            val oldLen = segLen(a = o0, b = o1)
            val newLen = segLen(a = n0, b = n1)
            if (k != s) {
                // a neighbour keeps its direction; if its length changed it stays long enough to be drawn
                val collapses = allowCollapse && k != 0 && k != last && newLen < EPS
                val sameDir = (n1.x - n0.x) * (o1.x - o0.x) + (n1.y - n0.y) * (o1.y - o0.y) > 0f
                if (!sameDir && !collapses) return false
                if ((k == 0 || k == last) && newLen < MIN_END_PX && newLen < oldLen) return false
                if (!collapses && abs(newLen - oldLen) > EPS && newLen < MIN_END_PX && newLen < oldLen) return false
            }
            val ownSkip =
                if (k == 0) {
                    w.sourceRect
                } else if (k == last) {
                    w.targetRect
                } else {
                    null
                }
            for (r in rects) {
                if (r == ownSkip) continue
                if (hitsInterior(a = n0, b = n1, r = r)) return false
            }
        }
        w.pts = next
        return true
    }

    /**
     * True when moving the segment `a-b` to [newCoord] would put it on a subject frame border (within [FRAME_BAND_PX])
     * or on the other side of it, where it runs along the frame (its extent overlaps the frame's extent). Segments that
     * only cross the border at a right angle are not affected.
     */
    private fun violatesFrame(
        horizontal: Boolean,
        a: Point,
        b: Point,
        newCoord: Float,
        frames: List<Rect>,
    ): Boolean {
        val oldCoord = if (horizontal) a.y else a.x
        val segLo = if (horizontal) min(a.x, b.x) else min(a.y, b.y)
        val segHi = if (horizontal) max(a.x, b.x) else max(a.y, b.y)
        for (f in frames) {
            val extLo = if (horizontal) f.origin.x else f.origin.y
            val extHi = if (horizontal) f.origin.x + f.size.width else f.origin.y + f.size.height
            if (segHi <= extLo || segLo >= extHi) continue
            val lo = if (horizontal) f.origin.y else f.origin.x
            val hi = if (horizontal) f.origin.y + f.size.height else f.origin.x + f.size.width
            val before = frameSide(coord = oldCoord, lo = lo, hi = hi)
            val after = frameSide(coord = newCoord, lo = lo, hi = hi)
            if (after == 0 || (before != 0 && before != after)) return true
        }
        return false
    }

    /** -1 outside the border band, 1 inside, 0 on the border (within [FRAME_BAND_PX]). */
    private fun frameSide(
        coord: Float,
        lo: Float,
        hi: Float,
    ): Int =
        if (coord < lo - FRAME_BAND_PX || coord > hi + FRAME_BAND_PX) {
            -1
        } else if (coord > lo + FRAME_BAND_PX && coord < hi - FRAME_BAND_PX) {
            1
        } else {
            0
        }

    private fun hitsInterior(
        a: Point,
        b: Point,
        r: Rect,
    ): Boolean {
        val m = 0.5f
        val l = r.origin.x + m
        val t = r.origin.y + m
        val rr = r.origin.x + r.size.width - m
        val bb = r.origin.y + r.size.height - m
        if (rr <= l || bb <= t) return false
        val x0 = min(a.x, b.x)
        val x1 = max(a.x, b.x)
        val y0 = min(a.y, b.y)
        val y1 = max(a.y, b.y)
        return x1 > l && x0 < rr && y1 > t && y0 < bb
    }

    // ----------------------------------------------------------------- ellipses

    /**
     * Moves the end of every line at a use case along its last segment onto the ellipse outline (the line was routed to
     * the bounding box). Ends that cannot be projected (too close to the top/bottom or left/right extreme) stay.
     */
    fun clipToEllipses(
        routes: Map<EdgeId, EdgeRoute>,
        useCases: Map<NodeId, Rect>,
        ends: Map<EdgeId, Pair<NodeId, NodeId>>,
    ): Map<EdgeId, EdgeRoute> {
        val out = LinkedHashMap<EdgeId, EdgeRoute>()
        for ((id, r) in routes) {
            val e = ends[id]
            if (r !is EdgeRoute.OrthogonalRounded || e == null) {
                out[id] = r
                continue
            }
            val src = useCases[e.first]?.let { clip(end = r.source, next = r.waypoints.firstOrNull() ?: r.target, box = it) } ?: r.source
            val tgt = useCases[e.second]?.let { clip(end = r.target, next = r.waypoints.lastOrNull() ?: r.source, box = it) } ?: r.target
            out[id] = EdgeRoute.OrthogonalRounded(source = src, target = tgt, waypoints = r.waypoints, cornerRadiusPx = r.cornerRadiusPx)
        }
        return out
    }

    private fun clip(
        end: Point,
        next: Point,
        box: Rect,
    ): Point {
        val rx = box.size.width / 2f
        val ry = box.size.height / 2f
        val cx = box.origin.x + rx
        val cy = box.origin.y + ry
        val horizontal = abs(end.y - next.y) < EPS
        val vertical = abs(end.x - next.x) < EPS
        if (horizontal == vertical) return end
        if (horizontal) {
            val t = (end.y - cy) / ry
            if (abs(t) >= ELLIPSE_GRAZE) return end
            val half = rx * sqrt(1f - t * t)
            val x = if (next.x < end.x) cx - half else cx + half // the line arrives from `next`: from the left means the left half
            // only move inward (the line was routed to the box side) and never past the centre
            return if (abs(x - cx) <= abs(end.x - cx) + EPS && (next.x - x) * (next.x - end.x) > 0f) Point(x = x, y = end.y) else end
        }
        val t = (end.x - cx) / rx
        if (abs(t) >= ELLIPSE_GRAZE) return end
        val half = ry * sqrt(1f - t * t)
        val y = if (next.y < end.y) cy - half else cy + half
        return if (abs(y - cy) <= abs(end.y - cy) + EPS && (next.y - y) * (next.y - end.y) > 0f) Point(x = end.x, y = y) else end
    }

    // ------------------------------------------------------------------ helpers

    private fun segLen(
        a: Point,
        b: Point,
    ): Float = abs(a.x - b.x) + abs(a.y - b.y)

    private fun coordOf(
        a: Point,
        b: Point,
    ): Float = if (abs(a.y - b.y) < EPS) a.y else a.x

    /** Drops duplicate points and interior points that lie on a straight line (endpoints are kept). */
    fun simplify(points: List<Point>): MutableList<Point> {
        val out = ArrayList<Point>()
        for (p in points) if (out.lastOrNull()?.let { abs(it.x - p.x) < EPS && abs(it.y - p.y) < EPS } != true) out += p
        var i = 1
        while (i < out.size - 1) {
            val a = out[i - 1]
            val b = out[i]
            val c = out[i + 1]
            val sameX = abs(a.x - b.x) < EPS && abs(b.x - c.x) < EPS
            val sameY = abs(a.y - b.y) < EPS && abs(b.y - c.y) < EPS
            if (sameX || sameY) out.removeAt(i) else i++
        }
        return out
    }
}
