@file:Suppress("RequireNamedArguments")

package dev.kuml.layout.bridge.port

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.abs

/**
 * Orthogonal routing of IBD connectors around the parts of the diagram (PAL wave 2C-b1).
 *
 * The router works between two **stub points** (the port border point plus the outward normal times the stub length).
 * It first tries a fixed list of simple candidates (straight, L, Z) and takes the one with the fewest bends that does
 * not touch an obstacle. If every candidate collides, a grid A* over the obstacle edges is run. If even the search
 * runs into [MAX_NODES] (or the grid exceeds [MAX_GRID_STATES]), the candidate with the fewest collisions is returned and a warning is logged.
 *
 * Everything is deterministic: candidates are tried in a fixed order, the search breaks ties on grid indices, and no
 * hash-ordered collection is iterated.
 */
internal object IbdObstacleRouter {
    /** Clearance (px) kept between a route and every foreign part. */
    const val OBSTACLE_INFLATE_PX = 8f

    /** Clearance (px) kept between a route and the two parts it connects (their stubs live inside the foreign clearance). */
    const val END_OBSTACLE_INFLATE_PX = 2f

    /** Clearance (px) kept between a route and a label / foreign port square passed as extra obstacle. */
    const val EXTRA_OBSTACLE_INFLATE_PX = 2f

    /** Cost (px equivalent) of one bend in the A* search. */
    const val BEND_COST_PX = 20f

    /** Hard limit of expanded search states (DoS guard). */
    const val MAX_NODES = 2000

    /** Hard limit of grid states (cells * 4 directions) the search may allocate (DoS guard); above it the search is skipped. */
    const val MAX_GRID_STATES = 200_000

    private const val EPS = 0.01f
    private const val GRID_MARGIN_PX = 16f
    private const val REGION_MARGIN_PX = 40f

    private val logger = KotlinLogging.logger {}

    private val dirs = listOf(Point(1f, 0f), Point(0f, 1f), Point(-1f, 0f), Point(0f, -1f))

    /**
     * Route between the stub points [from] and [to]. [fromDir] / [toDir] are the outward port normals (the first
     * segment must not run against [fromDir], the last must not run along [toDir]). [obstacles] are the foreign part
     * bounds, [endObstacles] the bounds of the two connected parts. [extraObstacles] are
     * further rectangles to keep clear of (port labels, foreign port squares); those that already contain [from] or [to]
     * are ignored. Returns the polyline including [from] and [to].
     */
    fun route(
        from: Point,
        fromDir: Point,
        to: Point,
        toDir: Point,
        obstacles: List<Rect>,
        endObstacles: List<Rect> = emptyList(),
        forceSearch: Boolean = false,
        extraObstacles: List<Rect> = emptyList(),
    ): List<Point> {
        val partsBlocked = obstacles.map { inflate(it, OBSTACLE_INFLATE_PX) } + endObstacles.map { inflate(it, END_OBSTACLE_INFLATE_PX) }
        val extra =
            relevantExtras(extraObstacles = extraObstacles, from = from, to = to, parts = partsBlocked)
                .map { inflate(it, EXTRA_OBSTACLE_INFLATE_PX) }
        val blocked = partsBlocked + extra
        val valid = candidates(from, to).filter { directionsOk(it, fromDir, toDir) }
        if (!forceSearch) {
            val clear = valid.filter { cand -> collisions(cand, blocked) == 0 }
            val best = clear.minWithOrNull(compareBy({ bends(it, fromDir, toDir) }, { perpendicularEnds(it, fromDir, toDir) }))
            if (best != null) return best
        }
        val searched = aStar(from, to, blocked, fromDir, toDir)
        if (searched != null) return searched
        val fallback =
            (valid.ifEmpty { candidates(from, to) }).minWithOrNull(compareBy({ collisions(it, blocked) }, { bends(it, fromDir, toDir) }))
                ?: listOf(from, to)
        logger.warn { "IBD obstacle router: search limit reached, using least-colliding candidate (${collisions(fallback, blocked)} hits)" }
        return fallback
    }

    /**
     * The extra obstacles that matter for a route between [from] and [to]: those not already containing one of the
     * two stub points. Unfiltered by distance; see [relevantExtras] for the search-region pruning.
     */
    fun extrasClearOfEnds(
        extraObstacles: List<Rect>,
        from: Point,
        to: Point,
    ): List<Rect> =
        extraObstacles.filter {
            val inflated = inflate(it, EXTRA_OBSTACLE_INFLATE_PX)
            !containsPoint(inflated, from) && !containsPoint(inflated, to)
        }

    /**
     * [extrasClearOfEnds] additionally pruned to the search region: the bounding box of [from] / [to] plus
     * [REGION_MARGIN_PX], grown by every part obstacle ([parts]) that touches it (a detour around a part may leave the
     * box). Extras outside that region cannot influence the route but would blow up the A* grid (each rectangle adds
     * coordinates on both axes), so they are dropped.
     */
    internal fun relevantExtras(
        extraObstacles: List<Rect>,
        from: Point,
        to: Point,
        parts: List<Rect>,
    ): List<Rect> {
        val clear = extrasClearOfEnds(extraObstacles, from, to)
        if (clear.isEmpty()) return clear
        var l = minOf(from.x, to.x) - REGION_MARGIN_PX
        var r = maxOf(from.x, to.x) + REGION_MARGIN_PX
        var t = minOf(from.y, to.y) - REGION_MARGIN_PX
        var b = maxOf(from.y, to.y) + REGION_MARGIN_PX
        val pending = parts.toMutableList()
        var grown = true
        while (grown && pending.isNotEmpty()) {
            grown = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val o = it.next()
                if (intersects(o, l, t, r, b)) {
                    l = minOf(l, o.origin.x - REGION_MARGIN_PX)
                    r = maxOf(r, o.origin.x + o.size.width + REGION_MARGIN_PX)
                    t = minOf(t, o.origin.y - REGION_MARGIN_PX)
                    b = maxOf(b, o.origin.y + o.size.height + REGION_MARGIN_PX)
                    it.remove()
                    grown = true
                }
            }
        }
        return clear.filter { intersects(inflate(it, EXTRA_OBSTACLE_INFLATE_PX), l, t, r, b) }
    }

    private fun intersects(
        o: Rect,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): Boolean = o.origin.x < r && o.origin.x + o.size.width > l && o.origin.y < b && o.origin.y + o.size.height > t

    /** Fixed candidate list: straight, L (horizontal first), L (vertical first), Z (50 % vertical line), Z (50 % horizontal line). */
    internal fun candidates(
        from: Point,
        to: Point,
    ): List<List<Point>> {
        val out = ArrayList<List<Point>>()
        val dx = abs(to.x - from.x) > EPS
        val dy = abs(to.y - from.y) > EPS
        if (!dx || !dy) {
            out += simplifyPath(listOf(from, to))
            return out
        }
        out += simplifyPath(listOf(from, Point(to.x, from.y), to))
        out += simplifyPath(listOf(from, Point(from.x, to.y), to))
        val mx = (from.x + to.x) / 2f
        val my = (from.y + to.y) / 2f
        out += simplifyPath(listOf(from, Point(mx, from.y), Point(mx, to.y), to))
        out += simplifyPath(listOf(from, Point(from.x, my), Point(to.x, my), to))
        return out
    }

    /**
     * Grid A* over the obstacle edges (plus the end points and a margin around everything). Returns null if the start
     * or goal is unreachable or [MAX_NODES] states were expanded.
     */
    internal fun aStar(
        from: Point,
        to: Point,
        obstacles: List<Rect>,
        fromDir: Point = Point(0f, 0f),
        toDir: Point = Point(0f, 0f),
    ): List<Point>? {
        val xsRaw = ArrayList<Float>()
        val ysRaw = ArrayList<Float>()
        xsRaw += from.x
        xsRaw += to.x
        ysRaw += from.y
        ysRaw += to.y
        var minX = minOf(from.x, to.x)
        var maxX = maxOf(from.x, to.x)
        var minY = minOf(from.y, to.y)
        var maxY = maxOf(from.y, to.y)
        for (o in obstacles) {
            xsRaw += o.origin.x
            xsRaw += o.origin.x + o.size.width
            ysRaw += o.origin.y
            ysRaw += o.origin.y + o.size.height
            minX = minOf(minX, o.origin.x)
            maxX = maxOf(maxX, o.origin.x + o.size.width)
            minY = minOf(minY, o.origin.y)
            maxY = maxOf(maxY, o.origin.y + o.size.height)
        }
        xsRaw += minX - GRID_MARGIN_PX
        xsRaw += maxX + GRID_MARGIN_PX
        ysRaw += minY - GRID_MARGIN_PX
        ysRaw += maxY + GRID_MARGIN_PX
        val xs = distinctSorted(xsRaw)
        val ys = distinctSorted(ysRaw)
        val nx = xs.size
        val ny = ys.size
        // DoS guard: the state arrays are dense, so refuse grids that would need too much memory.
        if (nx.toLong() * ny.toLong() * 4L > MAX_GRID_STATES) return null
        val startI = indexOf(xs, from.x)
        val startJ = indexOf(ys, from.y)
        val goalI = indexOf(xs, to.x)
        val goalJ = indexOf(ys, to.y)

        fun stateOf(
            i: Int,
            j: Int,
            d: Int,
        ): Int = (j * nx + i) * 4 + d

        val best = FloatArray(nx * ny * 4) { Float.POSITIVE_INFINITY }
        val parent = IntArray(nx * ny * 4) { -1 }

        class Entry(
            val f: Float,
            val g: Float,
            val state: Int,
        )

        val open = ArrayList<Entry>()
        val startDir = if (fromDir.x == 0f && fromDir.y == 0f) -1 else dirIndex(fromDir)

        fun heuristic(
            i: Int,
            j: Int,
        ): Float = abs(xs[i] - xs[goalI]) + abs(ys[j] - ys[goalJ])
        for (d in 0 until 4) {
            // Start state: the stub already runs along fromDir; leaving in another direction costs a bend.
            val g = if (startDir < 0 || d == startDir) 0f else BEND_COST_PX
            if (startDir >= 0 && d == (startDir + 2) % 4) continue
            val s = stateOf(startI, startJ, d)
            best[s] = g
            open += Entry(g + heuristic(startI, startJ), g, s)
        }
        val goalReverse = if (toDir.x == 0f && toDir.y == 0f) -1 else dirIndex(toDir)
        var expanded = 0
        var goalState = -1
        var goalBest = Float.POSITIVE_INFINITY
        while (open.isNotEmpty()) {
            // Deterministic selection: smallest f, then smallest state index.
            var bi = 0
            for (k in 1 until open.size) {
                val a = open[k]
                val b = open[bi]
                if (a.f < b.f || (a.f == b.f && a.state < b.state)) bi = k
            }
            val cur = open[bi]
            open[bi] = open[open.size - 1]
            open.removeAt(open.size - 1)
            if (cur.g > best[cur.state] + 1e-4f) continue
            if (cur.f >= goalBest) break
            if (++expanded > MAX_NODES) return null
            val d = cur.state % 4
            val cell = cur.state / 4
            val i = cell % nx
            val j = cell / nx
            if (i == goalI && j == goalJ) {
                // Arriving against the outward normal is a backtrack of the final stub.
                if (goalReverse >= 0 && d == goalReverse) {
                    // forbidden arrival
                } else {
                    val total = cur.g + (if (goalReverse >= 0 && d != (goalReverse + 2) % 4) BEND_COST_PX else 0f)
                    if (total < goalBest) {
                        goalBest = total
                        goalState = cur.state
                    }
                }
            }
            for (nd in 0 until 4) {
                if (nd == (d + 2) % 4) continue
                val ni = i + dirs[nd].x.toInt()
                val nj = j + dirs[nd].y.toInt()
                if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) continue
                val a = Point(xs[i], ys[j])
                val b = Point(xs[ni], ys[nj])
                if (obstacles.any { segmentHitsInterior(a, b, it) }) continue
                val ng = cur.g + abs(b.x - a.x) + abs(b.y - a.y) + (if (nd != d) BEND_COST_PX else 0f)
                val ns = stateOf(ni, nj, nd)
                if (ng < best[ns] - 1e-4f) {
                    best[ns] = ng
                    parent[ns] = cur.state
                    open += Entry(ng + heuristic(ni, nj), ng, ns)
                }
            }
        }
        if (goalState < 0) return null
        val pts = ArrayList<Point>()
        var s = goalState
        while (s >= 0) {
            val cell = s / 4
            pts += Point(xs[cell % nx], ys[cell / nx])
            s = parent[s]
        }
        pts.reverse()
        return simplifyPath(pts)
    }

    /** True when the segment `a`-`b` crosses the interior of [r], shrunk by [shrink] px (touching the border is no hit). */
    internal fun segmentHitsInterior(
        a: Point,
        b: Point,
        r: Rect,
        shrink: Float = 0.5f,
    ): Boolean {
        val l = r.origin.x + shrink
        val t = r.origin.y + shrink
        val rr = r.origin.x + r.size.width - shrink
        val bb = r.origin.y + r.size.height - shrink
        if (rr <= l || bb <= t) return false
        var t0 = 0f
        var t1 = 1f
        val dx = b.x - a.x
        val dy = b.y - a.y

        fun clip(
            p: Float,
            q: Float,
        ): Boolean {
            if (abs(p) < 1e-6f) return q > 0f
            val u = q / p
            if (p < 0f) {
                if (u >= t1) return false
                if (u > t0) t0 = u
            } else {
                if (u <= t0) return false
                if (u < t1) t1 = u
            }
            return true
        }
        return clip(-dx, a.x - l) && clip(dx, rr - a.x) && clip(-dy, a.y - t) && clip(dy, bb - a.y)
    }

    /** Number of path segments that hit any obstacle. */
    internal fun collisions(
        path: List<Point>,
        obstacles: List<Rect>,
    ): Int {
        var n = 0
        for (i in 0 until path.size - 1) {
            if (obstacles.any { segmentHitsInterior(path[i], path[i + 1], it) }) n++
        }
        return n
    }

    private fun directionsOk(
        path: List<Point>,
        fromDir: Point,
        toDir: Point,
    ): Boolean {
        if (path.size < 2) return true
        val first = unit(path[0], path[1])
        if (first.x * fromDir.x + first.y * fromDir.y < -0.5f) return false
        val last = unit(path[path.size - 2], path[path.size - 1])
        if (last.x * toDir.x + last.y * toDir.y > 0.5f) return false
        return true
    }

    /** Bend count of the full line including the two stub segments (a perpendicular first/last segment adds a bend). */
    private fun bends(
        path: List<Point>,
        fromDir: Point,
        toDir: Point,
    ): Int {
        if (path.size < 2) return 0
        var n = path.size - 2
        val first = unit(path[0], path[1])
        if (abs(first.x * fromDir.x + first.y * fromDir.y) < 0.5f) n++
        val last = unit(path[path.size - 2], path[path.size - 1])
        if (abs(last.x * toDir.x + last.y * toDir.y) < 0.5f) n++
        return n
    }

    /** Number of path ends (0..2) that do not run along the port normal; ports are entered straight by preference. */
    private fun perpendicularEnds(
        path: List<Point>,
        fromDir: Point,
        toDir: Point,
    ): Int {
        if (path.size < 2) return 0
        var n = 0
        val first = unit(path[0], path[1])
        if (abs(first.x * fromDir.x + first.y * fromDir.y) < 0.5f) n++
        val last = unit(path[path.size - 2], path[path.size - 1])
        if (abs(last.x * toDir.x + last.y * toDir.y) < 0.5f) n++
        return n
    }

    private fun unit(
        a: Point,
        b: Point,
    ): Point {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return if (abs(dx) >= abs(dy)) Point(if (dx >= 0f) 1f else -1f, 0f) else Point(0f, if (dy >= 0f) 1f else -1f)
    }

    private fun dirIndex(d: Point): Int =
        if (abs(d.x) >= abs(d.y)) {
            if (d.x >= 0f) 0 else 2
        } else if (d.y >= 0f) {
            1
        } else {
            3
        }

    private fun inflate(
        r: Rect,
        d: Float,
    ): Rect =
        Rect(
            origin = Point(r.origin.x - d, r.origin.y - d),
            size = Size(r.size.width + 2 * d, r.size.height + 2 * d),
        )

    private fun distinctSorted(v: List<Float>): List<Float> {
        val s = v.sorted()
        val out = ArrayList<Float>()
        for (x in s) if (out.isEmpty() || x - out.last() > EPS) out += x
        return out
    }

    private fun indexOf(
        sorted: List<Float>,
        v: Float,
    ): Int {
        var best = 0
        var bd = Float.MAX_VALUE
        for ((i, x) in sorted.withIndex()) {
            val d = abs(x - v)
            if (d < bd) {
                bd = d
                best = i
            }
        }
        return best
    }

    private fun containsPoint(
        r: Rect,
        p: Point,
    ): Boolean = p.x > r.origin.x && p.x < r.origin.x + r.size.width && p.y > r.origin.y && p.y < r.origin.y + r.size.height

    /** Removes repeated points and collinear interior points. */
    internal fun simplifyPath(pts: List<Point>): List<Point> {
        val dedup = ArrayList<Point>()
        for (p in pts) if (dedup.isEmpty() || abs(p.x - dedup.last().x) > EPS || abs(p.y - dedup.last().y) > EPS) dedup += p
        val out = ArrayList<Point>()
        for (p in dedup) {
            while (out.size >= 2) {
                val a = out[out.size - 2]
                val b = out.last()
                val sameX = abs(a.x - b.x) <= EPS && abs(b.x - p.x) <= EPS
                val sameY = abs(a.y - b.y) <= EPS && abs(b.y - p.y) <= EPS
                if (sameX || sameY) out.removeAt(out.size - 1) else break
            }
            out += p
        }
        return out
    }
}
