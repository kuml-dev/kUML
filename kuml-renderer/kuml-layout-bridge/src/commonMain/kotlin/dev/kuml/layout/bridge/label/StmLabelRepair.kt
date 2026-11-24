package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.ObstacleKind
import dev.kuml.layout.bridge.note.inflated
import dev.kuml.layout.bridge.note.intersects
import dev.kuml.layout.bridge.note.isLabel
import dev.kuml.layout.bridge.note.segmentIntersectsRect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * PAL Welle J2: repair pass of the state-machine transition labels. [LabelPlacer] places greedily and prices a collision;
 * a label that finds no free candidate then still ends up on another label or a foreign line. This pass takes exactly
 * those violators (and the label they collide with), removes them and puts them back with hard exclusions: a candidate
 * that touches a label, a node, an arrow head, a foreign line or the label's own line is never considered.
 *
 * Labels that are already clean never move (stability), so a diagram without a violator comes out unchanged. Order and
 * candidate set are fixed, so the result is deterministic. Work is bounded by [LabelCostModel.totalWorkBudget] and
 * [MAX_CANDIDATES] per label.
 */
internal object StmLabelRepair {
    /** Result of a repair: the placement of every label and the ids that still have no free position. */
    class Result(
        val placed: Map<String, PlacedLabel>,
        val unresolved: List<String>,
    )

    /** One position of a label (its rectangle and the polyline segment it sits beside). */
    class Cand(
        val rect: Rect,
        val segment: Int,
    )

    /** Work counter of one repair run (DoS guard). */
    class Budget(
        var left: Long,
    )

    /** Upper bound of the candidates tried per label. */
    const val MAX_CANDIDATES: Int = 400

    /** Two labels keep at least this many px apart. */
    private const val LABEL_GAP_PX: Float = 2f

    /** Fractions of the line length tried first (the middle, then both sides of it). */
    private val T_FRACTIONS: List<Float> = listOf(0.5f, 0.35f, 0.65f, 0.25f, 0.75f)

    /** Distance (px) between the line and the label edge facing it, nearest first. */
    private val OFFSETS: List<Float> = listOf(3f, 6f, 10f, 14f)

    private const val STEP_PX: Float = 8f
    private const val MAX_FRACTION: Float = 0.45f
    private const val MAX_STEPS: Int = 24
    private const val RIP_UP_TRIES: Int = 40
    private const val MAX_BLOCKERS: Int = 2

    /**
     * Repairs [placed0], the result of the greedy run over [requests]. [base] holds the nodes, composite frames, routes and
     * arrow heads, and must not contain any label.
     */
    fun repair(
        requests: List<LabelRequest>,
        placed0: Map<String, PlacedLabel>,
        base: ObstacleIndex,
        costs: LabelCostModel,
    ): Result {
        val reqs = requests.associateBy { it.id }
        val rects0 = placed0.mapValues { it.value.rect }
        val violators =
            placed0.keys
                .filter { id ->
                    val p = placed0.getValue(id)
                    !p.clean || rects0.any { (oid, r) -> oid != id && tooClose(a = p.rect, b = r) }
                }.sorted()
        if (violators.isEmpty()) return Result(placed = placed0, unresolved = emptyList())
        val budget = Budget(left = costs.totalWorkBudget)
        // Labels that are not violators stay where they are.
        val fixed = LinkedHashMap<String, Rect>()
        for ((id, r) in rects0) if (id !in violators) fixed[id] = r
        val repaired = LinkedHashMap<String, Cand>()
        val cache = HashMap<String, List<Cand>>()

        fun cands(id: String): List<Cand> = cache.getOrPut(id) { repairCandidates(reqs.getValue(id)) }

        fun others(
            id: String,
            withRepaired: Boolean = true,
        ): Map<String, Rect> {
            val m = LinkedHashMap<String, Rect>(fixed)
            if (withRepaired) for ((k, v) in repaired) m[k] = v.rect
            m.remove(id)
            return m
        }

        fun firstFree(
            id: String,
            first: Cand? = null,
        ): Cand? {
            val req = reqs.getValue(id)
            val around = others(id)
            if (first != null &&
                !isViolation(req = req, rect = first.rect, others = around, index = base, costs = costs, budget = budget)
            ) {
                return first
            }
            return cands(id).firstOrNull {
                budget.left > 0 && !isViolation(req = req, rect = it.rect, others = around, index = base, costs = costs, budget = budget)
            }
        }

        fun freeCount(id: String): Int {
            val req = reqs.getValue(id)
            val around = others(id, withRepaired = false)
            return cands(id).count {
                budget.left > 0 && !isViolation(req = req, rect = it.rect, others = around, index = base, costs = costs, budget = budget)
            }
        }

        // First the labels that were clean before (they take their old position back as soon as it is free), then the
        // others, the one with the fewest free positions first.
        val unresolved = ArrayList<String>()
        val wasClean = violators.filter { placed0.getValue(it).clean }
        val wasUnclean = violators.filter { !placed0.getValue(it).clean }
        for (id in wasClean) {
            val orig = Cand(rect = placed0.getValue(id).rect, segment = placed0.getValue(id).segment)
            val c = firstFree(id = id, first = orig)
            if (c != null) repaired[id] = c else unresolved += id
        }
        val rest = wasUnclean.toMutableList()
        while (rest.isNotEmpty()) {
            val next = rest.minWithOrNull(compareBy<String>({ freeCount(it) }, { it }))!!
            rest.remove(next)
            val c = firstFree(id = next)
            if (c != null) repaired[next] = c else unresolved += next
        }

        // One rip-up round: a still unplaced label may displace up to MAX_BLOCKERS labels that were repaired in this pass.
        val stillOpen = ArrayList<String>()
        for (u in unresolved.sorted()) {
            if (!ripUp(
                    u = u,
                    reqs = reqs,
                    fixed = fixed,
                    repaired = repaired,
                    cands = ::cands,
                    base = base,
                    costs = costs,
                    budget = budget,
                )
            ) {
                stillOpen += u
            }
        }

        val out = LinkedHashMap<String, PlacedLabel>()
        for ((id, p) in placed0) {
            val c = repaired[id]
            out[id] = if (c == null) p else PlacedLabel(rect = c.rect, onLine = false, clean = true, segment = c.segment)
        }
        return Result(placed = out, unresolved = stillOpen)
    }

    private fun ripUp(
        u: String,
        reqs: Map<String, LabelRequest>,
        fixed: Map<String, Rect>,
        repaired: MutableMap<String, Cand>,
        cands: (String) -> List<Cand>,
        base: ObstacleIndex,
        costs: LabelCostModel,
        budget: Budget,
    ): Boolean {
        val req = reqs.getValue(u)
        var tries = 0
        for (c in cands(u)) {
            if (budget.left <= 0 || tries >= RIP_UP_TRIES) return false
            // Free against everything that stays put (fixed labels and base obstacles); the repaired labels may be in the way.
            if (isViolation(req = req, rect = c.rect, others = fixed, index = base, costs = costs, budget = budget)) continue
            val blockers = repaired.filter { (_, v) -> tooClose(a = c.rect, b = v.rect) }.keys.sorted()
            if (blockers.isEmpty() || blockers.size > MAX_BLOCKERS) continue
            tries++
            val saved = blockers.associateWith { repaired.getValue(it) }
            for (b in blockers) repaired.remove(b)
            repaired[u] = c
            var ok = true
            for (b in blockers) {
                val around = LinkedHashMap<String, Rect>(fixed)
                for ((k, v) in repaired) if (k != b) around[k] = v.rect
                val breq = reqs.getValue(b)
                val spot =
                    cands(b).firstOrNull {
                        budget.left > 0 &&
                            !isViolation(req = breq, rect = it.rect, others = around, index = base, costs = costs, budget = budget)
                    }
                if (spot == null) {
                    ok = false
                    break
                }
                repaired[b] = spot
            }
            if (ok) return true
            // Roll back: nothing changes when the displaced labels find no place.
            repaired.remove(u)
            for (b in blockers) repaired.remove(b)
            repaired.putAll(saved)
        }
        return false
    }

    /** True when two label rectangles are closer than [LABEL_GAP_PX]. */
    private fun tooClose(
        a: Rect,
        b: Rect,
    ): Boolean = b.inflated(LABEL_GAP_PX).intersects(a)

    /**
     * True when [rect] is no acceptable position for the label [req]: it comes closer than 2 px to another label ([others]),
     * covers a node, composite title or arrow head, comes closer than the clearances to a foreign line, crosses its own line,
     * lies further than the cost model's distance from its own line, or sits at least as close to a foreign edge as to its
     * own. Each test counts against [budget].
     */
    fun isViolation(
        req: LabelRequest,
        rect: Rect,
        others: Map<String, Rect>,
        index: ObstacleIndex,
        costs: LabelCostModel,
        budget: Budget? = null,
    ): Boolean {
        budget?.let { it.left -= index.size + others.size + 1 }
        for (r in others.values) if (tooClose(a = rect, b = r)) return true
        for (o in index.rects) {
            if (o.kind.isLabel) continue
            val other = if (costs.nodeClearance > 0f) o.core.inflated(costs.nodeClearance) else o.core
            if (other.intersects(rect)) return true
        }
        for (a in req.avoid) if (a.intersects(rect)) return true
        val clearOwn = rect.inflated(costs.lineClearance)
        val clearForeign = rect.inflated(costs.foreignLineClearance)
        for (l in index.lines) {
            if (l.kind != ObstacleKind.EDGE && l.kind != ObstacleKind.FRAGMENT) continue
            val own = l.ownerId == req.ownerId
            if (own && !costs.ownCrossUnclean) continue
            val clear = if (own) clearOwn else clearForeign
            for (i in 0 until l.points.size - 1) {
                if (segmentIntersectsRect(a = l.points[i], b = l.points[i + 1], r = clear)) return true
            }
        }
        if (req.polyline.size >= 2) {
            val d = LabelPlacer.rectDistanceToPolyline(r = rect, pts = req.polyline)
            if (d > costs.maxDistancePx) return true
            if (LabelPlacer.foreignAsClose(r = rect, own = d, ownerId = req.ownerId, index = index)) return true
        }
        return false
    }

    /**
     * The positions of [req] in the order they are tried: along the line from its middle outwards (the fractions 0.5, 0.35,
     * 0.65, 0.25, 0.75, then every [STEP_PX] up to 0.45 of the length either side of the middle); equally far from the middle
     * a position beside a horizontal segment comes first. At each position the nearest offset first, the right of the
     * direction of travel before the left. At most [MAX_CANDIDATES].
     */
    fun repairCandidates(req: LabelRequest): List<Cand> {
        val pts = req.polyline
        if (pts.size < 2) return emptyList()
        val cum = FloatArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + LabelPlacer.len(a = pts[i - 1], b = pts[i])
        val total = cum.last()
        if (total < 0.01f) return emptyList()
        val mid = total / 2f
        val raw = ArrayList<Float>()
        raw += mid
        for (t in T_FRACTIONS.drop(1)) raw += total * t
        val steps = minOf(MAX_STEPS, floor(MAX_FRACTION * total / STEP_PX).toInt())
        for (k in 1..steps) {
            raw += mid + k * STEP_PX
            raw += mid - k * STEP_PX
        }

        fun segmentAt(s: Float): Int {
            var seg = 0
            while (seg < pts.size - 2 && cum[seg + 1] < s) seg++
            return seg
        }

        fun horizontal(s: Float): Int {
            val seg = segmentAt(s)
            return if (abs(pts[seg + 1].y - pts[seg].y) < 1f) 0 else 1
        }
        val ordered =
            raw
                .filter { it in 0f..total }
                .sortedWith(compareBy<Float>({ (abs(it - mid) / 2f).roundToInt() }, { horizontal(it) }, { it }))
        val accepted = ArrayList<Float>()
        for (s in ordered) if (accepted.none { abs(it - s) < 1f }) accepted += s
        val out = ArrayList<Cand>()
        for (s in accepted) {
            val seg = segmentAt(s)
            val a = pts[seg]
            val b = pts[seg + 1]
            val sl = cum[seg + 1] - cum[seg]
            val p: Point = LabelPlacer.pointAt(pts = pts, cum = cum, s = s)
            val ux = if (sl > 0.01f) (b.x - a.x) / sl else 1f
            val uy = if (sl > 0.01f) (b.y - a.y) / sl else 0f
            // Screen coordinates (y down): this normal points to the right of the direction of travel.
            val nx = -uy
            val ny = ux
            val half = abs(nx) * req.size.width / 2f + abs(ny) * req.size.height / 2f
            for (off in OFFSETS) {
                for (sign in intArrayOf(1, -1)) {
                    if (out.size >= MAX_CANDIDATES) return out
                    val d = sign * (off + half)
                    out += Cand(rect = LabelPlacer.centered(cx = p.x + nx * d, cy = p.y + ny * d, size = req.size), segment = seg)
                }
            }
        }
        return out
    }
}
