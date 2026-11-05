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
import kotlin.math.floor
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
 * @property outward the side of the owner box the port (or pin) sits on; with it the candidate order is the fixed
 *   ranking of PAL wave 2C-b2b (four outside positions, then four inside); without it the legacy four sides are used
 * @property parentId the owner box of a port label: obstacles owned by it (the box itself) do not count against the label
 * @property avoid rectangles this label must not cover (hard obstacles that exist only for this request, wave 2D-a)
 * @property segmentFlags per polyline segment a bit mask of [SEGMENT_SHARED] / [SEGMENT_SHORT] (used by [EdgeCandidateMode.ARC_LENGTH])
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
    val outward: LabelSide? = null,
    val parentId: String? = null,
    val avoid: List<Rect> = emptyList(),
    val segmentFlags: List<Int> = emptyList(),
)

/** Segment runs along a segment of another edge (bit of [LabelRequest.segmentFlags]). */
public const val SEGMENT_SHARED: Int = 1

/** Segment is too short to carry a label (bit of [LabelRequest.segmentFlags]). */
public const val SEGMENT_SHORT: Int = 2

/** How the candidates beside an edge are generated. */
public enum class EdgeCandidateMode {
    /** Fractions of the longest segments (PAR, ACT, BDD, IBD; the default). */
    SEGMENTS,

    /** Positions along the arc length of the whole polyline, nearest the middle first (state machines, wave 2D-a). */
    ARC_LENGTH,
}

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
    val lineClearance: Float = 2f,
    /** A port / pin label inside its owner box; chosen only when every outside position collides hard. */
    val innerPenalty: Float = 8_000f,
    /** Offsets (px, label edge to line) tried on each side of an edge, nearest first (PAL wave 2C-b3: parametric diagrams search further out). */
    val edgeOffsets: List<Float> = listOf(4f, 8f, 12f),
    /**
     * Free space a label keeps to nodes, port squares, texts and arrow squares: such a rectangle, inflated by this value,
     * counts as covered. `0` (default) keeps the exact rectangle test of the other diagram types.
     */
    val nodeClearance: Float = 0f,
    /** Candidate generation beside an edge; the default keeps PAR, ACT and BDD bit-identical. */
    val edgeCandidateMode: EdgeCandidateMode = EdgeCandidateMode.SEGMENTS,
    /** [EdgeCandidateMode.ARC_LENGTH]: distance in px between two candidate positions along the line. */
    val arcStepPx: Float = 8f,
    /** [EdgeCandidateMode.ARC_LENGTH]: positions reach this fraction of the line length either side of the middle. */
    val arcMaxFraction: Float = 0.4f,
    /** [EdgeCandidateMode.ARC_LENGTH]: label on the left of the direction of travel. */
    val leftSidePenalty: Float = 0f,
    /** [EdgeCandidateMode.ARC_LENGTH]: label beside a segment flagged [SEGMENT_SHARED]. */
    val sharedSegmentPenalty: Float = 0f,
    /** [EdgeCandidateMode.ARC_LENGTH]: label beside a segment flagged [SEGMENT_SHORT]. */
    val shortSegmentPenalty: Float = 0f,
    /** A foreign edge is at least as close to the label as its own edge. */
    val foreignCloserPenalty: Float = 0f,
    /** Soft penalty: a foreign edge is within [foreignNearMarginPx] beyond the owner's distance (label reads ambiguous). */
    val foreignNearPenalty: Float = 0f,
    /** Margin in px for [foreignNearPenalty]. */
    val foreignNearMarginPx: Float = 2f,
    /** When set, every label gets this work budget instead of sharing one budget (later ids no longer starve). */
    val perRequestWorkBudget: Long? = null,
    /**
     * Cap on the work of all labels together while [perRequestWorkBudget] is set (DoS guard: otherwise N labels cost N times
     * the per-label budget). Once spent, the remaining labels take their first candidate without a collision search.
     */
    val totalWorkBudget: Long = 4_000_000L,
)

/** A placed label: its rectangle and whether it sits on its own line (the renderer then needs the canvas-coloured halo). */
public data class PlacedLabel(
    val rect: Rect,
    val onLine: Boolean,
    /**
     * `false` when the label has no free spot: it sits on its own line (emergency candidate) or its cost shows it still
     * covers a node, text or foreign line (PAL wave 2C-b3). Only the parametric diagram reports this as a layout finding.
     */
    val clean: Boolean = true,
    /** [EdgeCandidateMode.ARC_LENGTH]: index of the polyline segment the label sits beside, otherwise `-1`. */
    val segment: Int = -1,
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
    private const val MIN_EXTRA_SEGMENT_PX: Float = 24f
    private val END_ALONG: List<Float> = listOf(6f, 10f, 14f, 22f, 32f, 44f, 60f, 80f)
    private val END_PERP: List<Float> = listOf(4f, 8f)
    private val PORT_GAPS: List<Float> = listOf(3f, 6f)

    /** Gap between a port / pin square and its label (PAL wave 2C-b2b; within the 2 to 12 px the design asks for). */
    public const val PORT_LABEL_GAP_PX: Float = 4f

    /** Distance between the owner box border and the label edge facing it for the labels beside a pin. */
    private const val BORDER_GAP_PX: Float = 2f

    private class Candidate(
        val rect: Rect,
        val onLine: Boolean,
        val sign: Int,
        val t: Float,
        val along: Float = 0f,
        val inner: Boolean = false,
        val segment: Int = -1,
        val left: Boolean = false,
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
        val perRequest = costs.perRequestWorkBudget
        var totalLeft = costs.totalWorkBudget
        for (req in ordered) {
            if (perRequest != null) work = minOf(perRequest, totalLeft)
            val workAtStart = work
            val candidates =
                candidatesOf(req = req, taken = multSign[req.ownerId to req.end]?.takeIf { req.role == LabelRole.ROLE }, costs = costs)
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
            if (perRequest != null) totalLeft -= (workAtStart - work).coerceAtLeast(0L)
            val chosen = best ?: candidates.firstOrNull() ?: continue
            val clean = !chosen.onLine && bestCost < minOf(costs.hardOverlap, costs.foreignEdge)
            result[req.id] = PlacedLabel(rect = chosen.rect, onLine = chosen.onLine, clean = clean, segment = chosen.segment)
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
        costs: LabelCostModel,
    ): List<Candidate> =
        when (req.role) {
            LabelRole.EDGE_CENTER ->
                if (costs.edgeCandidateMode == EdgeCandidateMode.ARC_LENGTH) {
                    arcCandidates(req = req, costs = costs)
                } else {
                    edgeCandidates(req = req, offsets = costs.edgeOffsets)
                }
            LabelRole.MULTIPLICITY, LabelRole.ROLE -> endCandidates(req = req, takenSign = taken)
            LabelRole.PORT -> portCandidates(req)
        }

    private fun edgeCandidates(
        req: LabelRequest,
        offsets: List<Float>,
    ): List<Candidate> {
        val pts = req.polyline
        if (pts.size < 2) return emptyList()
        // The longest segment first, then up to two more of at least MIN_EXTRA_SEGMENT_PX: a label whose best spot is
        // taken (a segment inside a box) can then use another stretch of its own line (PAL wave 2C-b2b).
        val ranked = (0 until pts.size - 1).sortedByDescending { len(a = pts[it], b = pts[it + 1]) }
        val segments = ranked.take(1) + ranked.drop(1).take(2).filter { len(a = pts[it], b = pts[it + 1]) >= MIN_EXTRA_SEGMENT_PX }
        val out = ArrayList<Candidate>(3 * EDGE_T.size * offsets.size * 2 + 1)
        var emergency: Candidate? = null
        for (bi in segments) {
            val bl = len(a = pts[bi], b = pts[bi + 1])
            val a = pts[bi]
            val b = pts[bi + 1]
            val ux = if (bl > 0.01f) (b.x - a.x) / bl else 1f
            val uy = if (bl > 0.01f) (b.y - a.y) / bl else 0f
            val nx = -uy
            val ny = ux
            // Ties between the two sides of a vertical line resolve to the right-hand side (like the horizontal case, where the
            // normal points down); a side that stays free of the notes anchored on the left keeps a label and a note apart.
            val sideOrder = if (abs(nx) > abs(ny) && nx < 0f) intArrayOf(-1, 1) else intArrayOf(1, -1)
            for (t in EDGE_T) {
                val px = a.x + (b.x - a.x) * t
                val py = a.y + (b.y - a.y) * t
                for (off in offsets) {
                    for (sign in sideOrder) {
                        val half = abs(nx) * req.size.width / 2f + abs(ny) * req.size.height / 2f
                        val d = sign * (off + half)
                        out +=
                            Candidate(
                                rect = centered(cx = px + nx * d, cy = py + ny * d, size = req.size),
                                onLine = false,
                                sign = sign,
                                t = t,
                            )
                    }
                }
            }
            if (emergency == null) {
                emergency =
                    Candidate(
                        rect = centered(cx = (a.x + b.x) / 2f, cy = (a.y + b.y) / 2f, size = req.size),
                        onLine = true,
                        sign = 0,
                        t = 0.5f,
                    )
            }
        }
        emergency?.let { out += it }
        return out
    }

    /** Hard cap of positions either side of the middle, so a hostile line cannot create an unbounded candidate list. */
    private const val ARC_MAX_STEPS: Int = 24

    /**
     * Candidates at positions along the arc length of the whole polyline (wave 2D-a): the middle first, then
     * alternately further along and back, up to [LabelCostModel.arcMaxFraction] of the length either side. Per position
     * the right-of-travel side comes before the left one; the offsets come nearest first. The emergency candidate sits
     * on the line at the middle.
     */
    private fun arcCandidates(
        req: LabelRequest,
        costs: LabelCostModel,
    ): List<Candidate> {
        val pts = req.polyline
        if (pts.size < 2) return emptyList()
        val cum = FloatArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + len(a = pts[i - 1], b = pts[i])
        val total = cum.last()
        if (total < 0.01f) return emptyList()
        val step = costs.arcStepPx.coerceAtLeast(1f)
        val steps = minOf(ARC_MAX_STEPS, floor(costs.arcMaxFraction.coerceIn(0f, 0.5f) * total / step).toInt())
        val positions = ArrayList<Float>(2 * steps + 1)
        positions += total / 2f
        for (k in 1..steps) {
            positions += total / 2f + k * step
            positions += total / 2f - k * step
        }
        val out = ArrayList<Candidate>(positions.size * costs.edgeOffsets.size * 2 + 1)
        for (s in positions) {
            var seg = 0
            while (seg < pts.size - 2 && cum[seg + 1] < s) seg++
            val a = pts[seg]
            val b = pts[seg + 1]
            val sl = cum[seg + 1] - cum[seg]
            val f = if (sl > 0.01f) ((s - cum[seg]) / sl).coerceIn(0f, 1f) else 0f
            val px = a.x + (b.x - a.x) * f
            val py = a.y + (b.y - a.y) * f
            val ux = if (sl > 0.01f) (b.x - a.x) / sl else 1f
            val uy = if (sl > 0.01f) (b.y - a.y) / sl else 0f
            // Screen coordinates (y down): this normal points to the right of the direction of travel.
            val nx = -uy
            val ny = ux
            val half = abs(nx) * req.size.width / 2f + abs(ny) * req.size.height / 2f
            for (off in costs.edgeOffsets) {
                for (sign in intArrayOf(1, -1)) {
                    val d = sign * (off + half)
                    out +=
                        Candidate(
                            rect = centered(cx = px + nx * d, cy = py + ny * d, size = req.size),
                            onLine = false,
                            sign = sign,
                            t = s / total,
                            segment = seg,
                            left = sign < 0,
                        )
                }
            }
        }
        var mseg = 0
        while (mseg < pts.size - 2 && cum[mseg + 1] < total / 2f) mseg++
        val mid = pointAt(pts = pts, cum = cum, s = total / 2f)
        out += Candidate(rect = centered(cx = mid.x, cy = mid.y, size = req.size), onLine = true, sign = 0, t = 0.5f, segment = mseg)
        return out
    }

    private fun pointAt(
        pts: List<Point>,
        cum: FloatArray,
        s: Float,
    ): Point {
        var seg = 0
        while (seg < pts.size - 2 && cum[seg + 1] < s) seg++
        val sl = cum[seg + 1] - cum[seg]
        val f = if (sl > 0.01f) ((s - cum[seg]) / sl).coerceIn(0f, 1f) else 0f
        return Point(x = pts[seg].x + (pts[seg + 1].x - pts[seg].x) * f, y = pts[seg].y + (pts[seg + 1].y - pts[seg].y) * f)
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
        val outward = req.outward ?: return legacyPortCandidates(req = req, anchor = anchor)
        val gap = PORT_LABEL_GAP_PX
        val w = req.size.width
        val h = req.size.height
        val out = ArrayList<Candidate>(8)
        // Outward unit vector, clockwise tangent along the box border (north: east, east: south, south: west, west: north).
        val (ox, oy, tx, ty) =
            when (outward) {
                LabelSide.NORTH -> listOf(0f, -1f, 1f, 0f)
                LabelSide.EAST -> listOf(1f, 0f, 0f, 1f)
                LabelSide.SOUTH -> listOf(0f, 1f, -1f, 0f)
                LabelSide.WEST -> listOf(-1f, 0f, 0f, -1f)
            }
        val halfPin = anchor.size.width / 2f
        val extO = abs(ox) * w / 2f + abs(oy) * h / 2f
        val extT = abs(tx) * w / 2f + abs(ty) * h / 2f
        // The port square sits on the border line: its centre is the border point.
        val cx = anchor.cx
        val cy = anchor.cy
        for (inner in booleanArrayOf(false, true)) {
            val dir = if (inner) -1f else 1f
            // Excluded docking side: where the own edge runs, the label does not stand directly in front of it.
            val dockedOnDirection =
                req.excludedSide != null &&
                    req.excludedSide ==
                    when {
                        ox > 0f -> if (inner) LabelSide.WEST else LabelSide.EAST
                        ox < 0f -> if (inner) LabelSide.EAST else LabelSide.WEST
                        oy > 0f -> if (inner) LabelSide.NORTH else LabelSide.SOUTH
                        else -> if (inner) LabelSide.SOUTH else LabelSide.NORTH
                    }
            // 1. clockwise behind the pin, 2. counter clockwise: beside the pin along the border, clear of the border line.
            for (sgn in floatArrayOf(1f, -1f)) {
                val centreT = sgn * (halfPin + gap + extT)
                val centreO = dir * (BORDER_GAP_PX + extO)
                out +=
                    Candidate(
                        rect = centered(cx = cx + tx * centreT + ox * centreO, cy = cy + ty * centreT + oy * centreO, size = req.size),
                        onLine = false,
                        sign = 0,
                        t = 0f,
                        inner = inner,
                    )
            }
            // 3. / 4. straight away from the pin, shifted to the left / up, then to the right / down.
            if (dockedOnDirection) continue
            for (sgn in floatArrayOf(-1f, 1f)) {
                // Shift perpendicular to the outward direction: left / up first, right / down second (screen coordinates).
                val shift = sgn * extT
                val sx = if (ox != 0f) 0f else 1f
                val sy = if (oy != 0f) 0f else 1f
                val centreO = dir * (halfPin + gap + extO)
                out +=
                    Candidate(
                        rect =
                            centered(
                                cx = cx + ox * centreO + sx * shift,
                                cy = cy + oy * centreO + sy * shift,
                                size = req.size,
                            ),
                        onLine = false,
                        sign = 0,
                        t = 0f,
                        inner = inner,
                    )
            }
        }
        return out
    }

    private fun legacyPortCandidates(
        req: LabelRequest,
        anchor: Rect,
    ): List<Candidate> {
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
            val other =
                when {
                    o.kind.isLabel -> o.core.inflated(maxOf(1f, costs.nodeClearance))
                    costs.nodeClearance > 0f -> o.core.inflated(costs.nodeClearance)
                    else -> o.core
                }
            if (!other.intersects(r)) continue
            // The owner's own port square / node is the thing the label belongs to: touching it is fine for ports.
            if (o.ownerId != null && req.role == LabelRole.PORT && (o.ownerId == req.ownerId || o.ownerId == req.parentId)) continue
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
        for (a in req.avoid) if (a.intersects(r)) cost += costs.hardOverlap
        if (c.segment >= 0 && !c.onLine) {
            if (c.left) cost += costs.leftSidePenalty
            val flags = req.segmentFlags.getOrElse(c.segment) { 0 }
            if (flags and SEGMENT_SHARED != 0) cost += costs.sharedSegmentPenalty
            if (flags and SEGMENT_SHORT != 0) cost += costs.shortSegmentPenalty
        }
        if (costs.foreignCloserPenalty > 0f && req.role == LabelRole.EDGE_CENTER && req.polyline.size >= 2 && !c.onLine) {
            val own = rectDistanceToPolyline(r = r, pts = req.polyline)
            if (foreignAsClose(r = r, own = own, ownerId = req.ownerId, index = index)) {
                cost += costs.foreignCloserPenalty
            } else if (costs.foreignNearPenalty > 0f &&
                foreignAsClose(r = r, own = own + costs.foreignNearMarginPx, ownerId = req.ownerId, index = index)
            ) {
                cost += costs.foreignNearPenalty
            }
        }
        cost += costs.perPxAlong * c.along
        if (c.onLine) cost += costs.onLinePenalty
        if (c.inner) cost += costs.innerPenalty
        return cost
    }

    /** True if a foreign edge polyline is at least as close to [r] as the owner's own line (distance [own]). */
    private fun foreignAsClose(
        r: Rect,
        own: Float,
        ownerId: String,
        index: ObstacleIndex,
    ): Boolean {
        val reach = r.inflated(own + 0.5f)
        for (l in index.lines) {
            if (l.kind != ObstacleKind.EDGE || l.ownerId == ownerId) continue
            var near = false
            for (i in 0 until l.points.size - 1) {
                if (segmentIntersectsRect(a = l.points[i], b = l.points[i + 1], r = reach)) {
                    near = true
                    break
                }
            }
            if (near && rectDistanceToPolyline(r = r, pts = l.points) <= own + 0.01f) return true
        }
        return false
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
