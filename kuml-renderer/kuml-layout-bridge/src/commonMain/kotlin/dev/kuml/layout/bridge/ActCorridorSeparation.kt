package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import kotlin.math.abs

/**
 * Pulls apart collinear edge segments (PAL wave 2F, A4). Two different edges that run on top of each other for more
 * than [MIN_OVERLAP_PX] get at least [SEPARATION_PX] distance: the later edge's interior segment is moved sideways
 * and its neighbouring segments follow, so the route stays orthogonal. First and last segments (pinned to a node) stay.
 * At most [MAX_PASSES] passes; deterministic (edges visited in id order).
 *
 * PAL Welle X3: with [obstacles] a shift that would drive the moved segment (or its neighbours) through a foreign node
 * is not taken; the segment goes to the other side of the segment it overlaps instead, or stays. Without a
 * node collision the shift is the unchanged one, so clean diagrams stay byte-identical.
 */
internal object ActCorridorSeparation {
    const val SEPARATION_PX = 12f
    const val MIN_OVERLAP_PX = 8f
    const val MAX_PASSES = 4
    private const val TOL = 0.75f

    private const val OBSTACLE_MARGIN = 5f

    fun apply(
        routes: Map<EdgeId, EdgeRoute>,
        obstacles: Map<String, Rect> = emptyMap(),
        endpoints: Map<EdgeId, Set<String>> = emptyMap(),
    ): Map<EdgeId, EdgeRoute> {
        val pts = LinkedHashMap<EdgeId, MutableList<Point>>()
        for ((id, r) in routes.entries.sortedBy { it.key.value }) {
            val p =
                when (r) {
                    is EdgeRoute.OrthogonalRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                    is EdgeRoute.TreeRounded -> listOf(r.source) + r.waypoints + listOf(r.target)
                    else -> continue
                }
            pts[id] = p.toMutableList()
        }
        val settled = HashSet<Pair<EdgeId, Int>>()
        for (pass in 0 until MAX_PASSES) {
            var changed = false
            val ids = pts.keys.toList()
            for (bi in ids.indices) {
                val b = pts.getValue(ids[bi])
                for (j in 1 until b.size - 2) { // interior segments only
                    val p = b[j]
                    val q = b[j + 1]
                    val vertical = abs(p.x - q.x) < TOL && abs(p.y - q.y) > TOL
                    val horizontal = abs(p.y - q.y) < TOL && abs(p.x - q.x) > TOL
                    if (!vertical && !horizontal) continue
                    if (ids[bi] to j in settled) continue
                    val foreign = obstacles.filterKeys { it !in endpoints[ids[bi]].orEmpty() }.values
                    for (ai in 0 until bi) {
                        val a = pts.getValue(ids[ai])
                        val hit = collinearOverlap(other = a, p = p, q = q, vertical = vertical) ?: continue
                        val plus = shifted(b = b, j = j, vertical = vertical, delta = SEPARATION_PX)
                        var chosen: List<Point>? = plus
                        if (foreign.isNotEmpty()) {
                            val own = if (vertical) p.x else p.y
                            val minus = shifted(b = b, j = j, vertical = vertical, delta = hit - SEPARATION_PX - own)
                            val hp = hits(b = plus, j = j, foreign = foreign)
                            val hm = hits(b = minus, j = j, foreign = foreign)
                            val h0 = hits(b = b, j = j, foreign = foreign)
                            // fewest node hits wins; ties keep the order plus, minus, stay (plus is the unchanged behaviour)
                            if (hm < hp && hm <= h0) {
                                chosen = minus
                                settled += ids[bi] to j
                            } else if (hp > h0) {
                                chosen = null
                            }
                        }
                        if (chosen != null) {
                            b[j] = chosen[j]
                            b[j + 1] = chosen[j + 1]
                            changed = true
                        }
                        break
                    }
                }
            }
            if (!changed) break
        }
        val out = LinkedHashMap(routes)
        for ((id, p) in pts) {
            val old = routes.getValue(id)
            val cleaned = simplify(p)
            out[id] =
                when (old) {
                    is EdgeRoute.OrthogonalRounded -> old.copy(waypoints = cleaned.drop(1).dropLast(1))
                    is EdgeRoute.TreeRounded -> old.copy(waypoints = cleaned.drop(1).dropLast(1))
                    else -> old
                }
        }
        return out
    }

    private fun collinearOverlap(
        other: List<Point>,
        p: Point,
        q: Point,
        vertical: Boolean,
    ): Float? {
        for (k in 0 until other.size - 1) {
            val s = other[k]
            val t = other[k + 1]
            if (vertical) {
                if (abs(s.x - t.x) >= TOL || abs(s.x - p.x) >= SEPARATION_PX - 1f) continue
                val lo = maxOf(minOf(p.y, q.y), minOf(s.y, t.y))
                val hi = minOf(maxOf(p.y, q.y), maxOf(s.y, t.y))
                if (hi - lo > MIN_OVERLAP_PX && abs(s.x - p.x) < SEPARATION_PX - 1f) return s.x
            } else {
                if (abs(s.y - t.y) >= TOL || abs(s.y - p.y) >= SEPARATION_PX - 1f) continue
                val lo = maxOf(minOf(p.x, q.x), minOf(s.x, t.x))
                val hi = minOf(maxOf(p.x, q.x), maxOf(s.x, t.x))
                if (hi - lo > MIN_OVERLAP_PX && abs(s.y - p.y) < SEPARATION_PX - 1f) return s.y
            }
        }
        return null
    }

    /** A copy of [b] with the interior segment j..j+1 moved by [delta] across its direction; the neighbours follow. */
    private fun shifted(
        b: List<Point>,
        j: Int,
        vertical: Boolean,
        delta: Float,
    ): List<Point> {
        val out = b.toMutableList()
        for (k in listOf(j, j + 1)) {
            val q = b[k]
            out[k] = if (vertical) Point(x = q.x + delta, y = q.y) else Point(x = q.x, y = q.y + delta)
        }
        return out
    }

    /** Number of (segment, foreign node) pairs among the segments j-1..j+1 that run through a node (with a margin). */
    private fun hits(
        b: List<Point>,
        j: Int,
        foreign: Collection<Rect>,
    ): Int {
        var n = 0
        for (k in maxOf(0, j - 1)..minOf(b.size - 2, j + 1)) {
            val p = b[k]
            val q = b[k + 1]
            for (r in foreign) {
                val x0 = r.origin.x - OBSTACLE_MARGIN
                val x1 = r.origin.x + r.size.width + OBSTACLE_MARGIN
                val y0 = r.origin.y - OBSTACLE_MARGIN
                val y1 = r.origin.y + r.size.height + OBSTACLE_MARGIN
                val inX = minOf(p.x, q.x) < x1 && maxOf(p.x, q.x) > x0
                val inY = minOf(p.y, q.y) < y1 && maxOf(p.y, q.y) > y0
                if (inX && inY) n++
            }
        }
        return n
    }

    private fun simplify(pts: List<Point>): List<Point> {
        val out = ArrayList<Point>()
        for (p in pts) {
            if (out.isNotEmpty() && abs(out.last().x - p.x) < 0.01f && abs(out.last().y - p.y) < 0.01f) continue
            while (out.size >= 2) {
                val a = out[out.size - 2]
                val b = out.last()
                val collinear = (abs(a.x - b.x) < 0.01f && abs(b.x - p.x) < 0.01f) || (abs(a.y - b.y) < 0.01f && abs(b.y - p.y) < 0.01f)
                if (collinear) out.removeAt(out.size - 1) else break
            }
            out += p
        }
        return out
    }
}
