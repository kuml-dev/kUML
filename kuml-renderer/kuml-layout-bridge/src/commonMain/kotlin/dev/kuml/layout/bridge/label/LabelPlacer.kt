package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.Size
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.bottom
import dev.kuml.layout.bridge.note.cx
import dev.kuml.layout.bridge.note.cy
import dev.kuml.layout.bridge.note.distanceToSegment
import dev.kuml.layout.bridge.note.inflated
import dev.kuml.layout.bridge.note.intersects
import dev.kuml.layout.bridge.note.isLabel
import dev.kuml.layout.bridge.note.left
import dev.kuml.layout.bridge.note.rectOf
import dev.kuml.layout.bridge.note.right
import dev.kuml.layout.bridge.note.segmentIntersectsRect
import dev.kuml.layout.bridge.note.top
import kotlin.math.abs
import kotlin.math.sqrt

/** What a label is (PAL wave 2B-b). */
public enum class LabelRole { EDGE_CENTER, MULTIPLICITY, ROLE, PORT }

/** Which end of an edge a [LabelRole.MULTIPLICITY] / [LabelRole.ROLE] label belongs to. */
public enum class LabelEnd { SOURCE, TARGET }

/** A side of a rectangle (port squares). */
public enum class LabelSide { NORTH, EAST, SOUTH, WEST }

/**
 * One label to place.
 *
 * @property id stable, sortable id of the label (the result is keyed by it)
 * @property role the label's kind; decides the candidate set
 * @property ownerId the edge id (or port id) the label belongs to; its own line is only a soft obstacle
 * @property size estimated, font independent label size
 * @property polyline the owner edge polyline (unused for [LabelRole.PORT])
 * @property end the edge end of a multiplicity / role label
 * @property anchorRect the port square of a [LabelRole.PORT] label
 * @property excludedSide the side of the port square the label must not use (where the edge docks)
 */
public data class LabelRequest(
    val id: String,
    val role: LabelRole,
    val ownerId: String,
    val size: Size,
    val polyline: List<Point> = emptyList(),
    val end: LabelEnd? = null,
    val anchorRect: Rect? = null,
    val excludedSide: LabelSide? = null,
)

/** Penalties of the label cost model; configuration, not code. */
public data class LabelCostModel(
    /** Covering a node, port, note or another label. */
    val hardOverlap: Float = 50_000f,
    /** Crossing the line of a foreign edge. */
    val foreignEdge: Float = 20_000f,
    /** Crossing the owner's own line without being a deliberate on-line candidate. */
    val ownEdge: Float = 10_000f,
    /** Per px of distance between label and owner line. */
    val perPxDistance: Float = 100f,
    /** Per tenth of the segment the label is off its centre. */
    val perTenthOffCenter: Float = 200f,
    /** The emergency candidate that sits on the line (drawn with a halo). */
    val onLinePenalty: Float = 5_000f,
    /** Distance beyond which a label no longer clearly belongs to its line. */
    val maxDistancePx: Float = 12f,
    /** Per px a multiplicity / role label sits away from its edge end (prefers the end when nothing else differs). */
    val perPxAlong: Float = 5f,
    /** Foreign line closer than this to a label counts as touching. */
    val lineClearance: Float = 1f,
)

/** A placed label: its rectangle and whether it sits on its own line (the renderer then needs the canvas-coloured halo). */
public data class PlacedLabel(
    val rect: Rect,
    val onLine: Boolean,
)

/**
 * Places labels in a fixed order with a cost model over a fixed candidate set (PAL wave 2B-b): port labels, then
 * multiplicities and roles, then centre labels; inside a stage ordered by id. Every placed label becomes an
 * obstacle of the next one. Ties resolve to the lowest candidate index, so the result is deterministic. All loops
 * are bounded by the candidate count and a work budget; when the budget runs out the best candidate so far is kept.
 */
public object LabelPlacer {
    private const val WORK_BUDGET: Long = 4_000_000L
    private val EDGE_T: List<Float> = listOf(0.5f, 0.35f, 0.65f, 0.2f, 0.8f)
    private val EDGE_OFFSETS: List<Float> = listOf(4f, 8f, 12f)
    private val END_ALONG: List<Float> = listOf(6f, 10f, 14f, 22f, 32f, 44f, 60f, 80f)
    private val END_PERP: List<Float> = listOf(4f, 8f)
    private val PORT_GAPS: List<Float> = listOf(3f, 6f)

    private class Candidate(
        val rect: Rect,
        val onLine: Boolean,
        val sign: Int,
        val t: Float,
        val along: Float = 0f,
    )

    /** Places [requests]; the placed labels are added to [index] as obstacles. */
    public fun place(
        requests: List<LabelRequest>,
        index: ObstacleIndex,
        costs: LabelCostModel = LabelCostModel(),
    ): Map<String, PlacedLabel> {
        val result = LinkedHashMap<String, PlacedLabel>()
        var work = WORK_BUDGET
        val stage = { r: LabelRequest ->
            when (r.role) {
                LabelRole.PORT -> 0
                LabelRole.MULTIPLICITY -> 1
                LabelRole.ROLE -> 2
                LabelRole.EDGE_CENTER -> 3
            }
        }
        val ordered = requests.sortedWith(compareBy({ stage(it) }, { it.ownerId }, { it.end?.ordinal ?: 0 }, { it.id }))
        // (owner, end) -> side sign taken by the multiplicity, so the role goes to the other side
        val multSign = HashMap<Pair<String, LabelEnd?>, Int>()
        for (req in ordered) {
            val candidates = candidatesOf(req = req, taken = multSign[req.ownerId to req.end]?.takeIf { req.role == LabelRole.ROLE })
            var best: Candidate? = null
            var bestCost = Float.MAX_VALUE
            for (c in candidates) {
                if (work <= 0) break
                val cost = cost(c = c, req = req, index = index, costs = costs)
                work -= index.size + 1
                if (cost < bestCost) {
                    bestCost = cost
                    best = c
                }
            }
            val chosen = best ?: candidates.firstOrNull() ?: continue
            result[req.id] = PlacedLabel(rect = chosen.rect, onLine = chosen.onLine)
            if (req.role == LabelRole.MULTIPLICITY) multSign[req.ownerId to req.end] = chosen.sign
            index.addRect(kind = kindOf(req.role), r = chosen.rect, ownerId = req.ownerId)
        }
        return result
    }

    private fun kindOf(role: LabelRole): ObstacleKind =
        when (role) {
            LabelRole.EDGE_CENTER -> ObstacleKind.EDGE_LABEL
            LabelRole.MULTIPLICITY -> ObstacleKind.MULTIPLICITY
            LabelRole.ROLE -> ObstacleKind.ROLE
            LabelRole.PORT -> ObstacleKind.PORT_LABEL
        }

    // ── Candidates ───────────────────────────────────────────────────────────────────────────────

    private fun candidatesOf(
        req: LabelRequest,
        taken: Int?,
    ): List<Candidate> =
        when (req.role) {
            LabelRole.EDGE_CENTER -> edgeCandidates(req)
            LabelRole.MULTIPLICITY, LabelRole.ROLE -> endCandidates(req = req, takenSign = taken)
            LabelRole.PORT -> portCandidates(req)
        }

    private fun edgeCandidates(req: LabelRequest): List<Candidate> {
        val pts = req.polyline
        if (pts.size < 2) return emptyList()
        var bi = 0
        var bl = -1f
        for (i in 0 until pts.size - 1) {
            val l = len(a = pts[i], b = pts[i + 1])
            if (l > bl) {
                bl = l
                bi = i
            }
        }
        val a = pts[bi]
        val b = pts[bi + 1]
        val ux = if (bl > 0.01f) (b.x - a.x) / bl else 1f
        val uy = if (bl > 0.01f) (b.y - a.y) / bl else 0f
        val nx = -uy
        val ny = ux
        val out = ArrayList<Candidate>(31)
        for (t in EDGE_T) {
            val px = a.x + (b.x - a.x) * t
            val py = a.y + (b.y - a.y) * t
            for (off in EDGE_OFFSETS) {
                for (sign in intArrayOf(1, -1)) {
                    val half = abs(nx) * req.size.width / 2f + abs(ny) * req.size.height / 2f
                    val d = sign * (off + half)
                    out +=
                        Candidate(rect = centered(cx = px + nx * d, cy = py + ny * d, size = req.size), onLine = false, sign = sign, t = t)
                }
            }
        }
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        out += Candidate(rect = centered(cx = mx, cy = my, size = req.size), onLine = true, sign = 0, t = 0.5f)
        return out
    }

    private fun endCandidates(
        req: LabelRequest,
        takenSign: Int?,
    ): List<Candidate> {
        val pts = req.polyline
        if (pts.size < 2) return emptyList()
        val source = req.end != LabelEnd.TARGET
        val base = if (source) pts.first() else pts.last()
        val next = if (source) pts[1] else pts[pts.size - 2]
        val l = len(a = base, b = next)
        val ux = if (l > 0.01f) (next.x - base.x) / l else 1f
        val uy = if (l > 0.01f) (next.y - base.y) / l else 0f
        val nx = -uy
        val ny = ux
        val out = ArrayList<Candidate>(48)
        val halfAlong = abs(ux) * req.size.width / 2f + abs(uy) * req.size.height / 2f
        val halfPerp = abs(nx) * req.size.width / 2f + abs(ny) * req.size.height / 2f
        // Candidates stay on the end segment: further along the label would sit across the next bend of its own line.
        val reach = END_ALONG.filter { it + 2f * halfAlong <= l }.ifEmpty { END_ALONG.take(1) }
        for (sign in intArrayOf(1, -1)) {
            if (takenSign != null && sign == takenSign) continue
            for (along in reach) {
                for (perp in END_PERP) {
                    val da = along + halfAlong
                    val dp = sign * (perp + halfPerp)
                    out +=
                        Candidate(
                            rect = centered(cx = base.x + ux * da + nx * dp, cy = base.y + uy * da + ny * dp, size = req.size),
                            onLine = false,
                            sign = sign,
                            t = 0f,
                            along = along,
                        )
                }
            }
        }
        return out
    }

    private fun portCandidates(req: LabelRequest): List<Candidate> {
        val anchor = req.anchorRect ?: return emptyList()
        val out = ArrayList<Candidate>(8)
        for (side in LabelSide.entries) {
            if (side == req.excludedSide) continue
            for (gap in PORT_GAPS) {
                val c =
                    when (side) {
                        LabelSide.NORTH -> centered(cx = anchor.cx, cy = anchor.top - gap - req.size.height / 2f, size = req.size)
                        LabelSide.SOUTH -> centered(cx = anchor.cx, cy = anchor.bottom + gap + req.size.height / 2f, size = req.size)
                        LabelSide.WEST -> centered(cx = anchor.left - gap - req.size.width / 2f, cy = anchor.cy, size = req.size)
                        LabelSide.EAST -> centered(cx = anchor.right + gap + req.size.width / 2f, cy = anchor.cy, size = req.size)
                    }
                out += Candidate(rect = c, onLine = false, sign = 0, t = 0f)
            }
        }
        return out
    }

    // ── Cost ─────────────────────────────────────────────────────────────────────────────────────

    private fun cost(
        c: Candidate,
        req: LabelRequest,
        index: ObstacleIndex,
        costs: LabelCostModel,
    ): Float {
        val r = c.rect
        var cost = 0f
        for (o in index.rects) {
            val other = if (o.kind.isLabel) o.core.inflated(1f) else o.core
            if (!other.intersects(r)) continue
            // The owner's own port square / node is the thing the label belongs to: touching it is fine for ports.
            if (o.ownerId != null && o.ownerId == req.ownerId && req.role == LabelRole.PORT) continue
            cost += costs.hardOverlap
        }
        val clear = r.inflated(costs.lineClearance)
        for (l in index.lines) {
            if (l.kind != ObstacleKind.EDGE && l.kind != ObstacleKind.LIFELINE && l.kind != ObstacleKind.FRAGMENT) continue
            var hit = false
            for (i in 0 until l.points.size - 1) {
                if (segmentIntersectsRect(a = l.points[i], b = l.points[i + 1], r = clear)) {
                    hit = true
                    break
                }
            }
            if (!hit) continue
            cost += if (l.ownerId == req.ownerId) (if (c.onLine) 0f else costs.ownEdge) else costs.foreignEdge
        }
        if (req.role != LabelRole.PORT && req.polyline.size >= 2) {
            val d = rectDistanceToPolyline(r = r, pts = req.polyline)
            cost += costs.perPxDistance * (d / 10f)
            if (d > costs.maxDistancePx) cost += costs.perPxDistance * (d - costs.maxDistancePx)
        }
        if (req.role == LabelRole.EDGE_CENTER) cost += costs.perTenthOffCenter * (abs(c.t - 0.5f) * 10f)
        cost += costs.perPxAlong * c.along
        if (c.onLine) cost += costs.onLinePenalty
        return cost
    }

    /** Shortest distance between [r] and the polyline [pts]; `0` if they touch or cross. */
    public fun rectDistanceToPolyline(
        r: Rect,
        pts: List<Point>,
    ): Float {
        var best = Float.MAX_VALUE
        val corners =
            listOf(
                Point(x = r.left, y = r.top),
                Point(x = r.right, y = r.top),
                Point(x = r.right, y = r.bottom),
                Point(x = r.left, y = r.bottom),
            )
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (segmentIntersectsRect(a = a, b = b, r = r)) return 0f
            best = minOf(best, pointToRect(p = a, r = r), pointToRect(p = b, r = r))
            for (c in corners) best = minOf(best, distanceToSegment(p = c, a = a, b = b))
        }
        return best
    }

    private fun pointToRect(
        p: Point,
        r: Rect,
    ): Float {
        val dx = maxOf(r.left - p.x, 0f, p.x - r.right)
        val dy = maxOf(r.top - p.y, 0f, p.y - r.bottom)
        return sqrt(dx * dx + dy * dy)
    }

    private fun centered(
        cx: Float,
        cy: Float,
        size: Size,
    ): Rect = rectOf(left = cx - size.width / 2f, top = cy - size.height / 2f, right = cx + size.width / 2f, bottom = cy + size.height / 2f)

    private fun len(
        a: Point,
        b: Point,
    ): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
