package dev.kuml.layout.bridge

import dev.kuml.layout.EdgeId
import dev.kuml.layout.EdgeRoute
import dev.kuml.layout.Point
import kotlin.math.abs

/**
 * Pulls apart collinear edge segments (PAL wave 2F, A4). Two different edges that run on top of each other for more
 * than [MIN_OVERLAP_PX] get at least [SEPARATION_PX] distance: the later edge's interior segment is moved sideways
 * and its neighbouring segments follow, so the route stays orthogonal. First and last segments (pinned to a node) stay.
 * At most [MAX_PASSES] passes; deterministic (edges visited in id order).
 */
internal object ActCorridorSeparation {
    const val SEPARATION_PX = 12f
    const val MIN_OVERLAP_PX = 8f
    const val MAX_PASSES = 4
    private const val TOL = 0.75f

    fun apply(routes: Map<EdgeId, EdgeRoute>): Map<EdgeId, EdgeRoute> {
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
                    for (ai in 0 until bi) {
                        val a = pts.getValue(ids[ai])
                        if (collinearOverlap(other = a, p = p, q = q, vertical = vertical)) {
                            if (vertical) {
                                b[j] = Point(x = p.x + SEPARATION_PX, y = p.y)
                                b[j + 1] = Point(x = q.x + SEPARATION_PX, y = q.y)
                            } else {
                                b[j] = Point(x = p.x, y = p.y + SEPARATION_PX)
                                b[j + 1] = Point(x = q.x, y = q.y + SEPARATION_PX)
                            }
                            changed = true
                            break
                        }
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
    ): Boolean {
        for (k in 0 until other.size - 1) {
            val s = other[k]
            val t = other[k + 1]
            if (vertical) {
                if (abs(s.x - t.x) >= TOL || abs(s.x - p.x) >= SEPARATION_PX - 1f) continue
                val lo = maxOf(minOf(p.y, q.y), minOf(s.y, t.y))
                val hi = minOf(maxOf(p.y, q.y), maxOf(s.y, t.y))
                if (hi - lo > MIN_OVERLAP_PX && abs(s.x - p.x) < SEPARATION_PX - 1f) return true
            } else {
                if (abs(s.y - t.y) >= TOL || abs(s.y - p.y) >= SEPARATION_PX - 1f) continue
                val lo = maxOf(minOf(p.x, q.x), minOf(s.x, t.x))
                val hi = minOf(maxOf(p.x, q.x), maxOf(s.x, t.x))
                if (hi - lo > MIN_OVERLAP_PX && abs(s.y - p.y) < SEPARATION_PX - 1f) return true
            }
        }
        return false
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
