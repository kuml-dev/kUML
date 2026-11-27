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
        /** PAL Welle L: label id to the index of the narrower variant it was put back with (labels that kept their size are absent). */
        val chosen: Map<String, Int> = emptyMap(),
        /** PAL Welle L: unresolved label id to the short token of what blocks most of its positions. */
        val reason: Map<String, String> = emptyMap(),
        /** PAL Welle M: unresolved label id to its blocking obstacles as `kind id`, most frequent first (all of them; the log shortens). */
        val blockers: Map<String, List<String>> = emptyMap(),
    )

    /** One position of a label (its rectangle, the polyline segment it sits beside and the narrower variant it was sized for, -1 = original). */
    class Cand(
        val rect: Rect,
        val segment: Int,
        val variant: Int = -1,
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

    /** PAL Welle L: the single last-resort stage tries one more offset and accepts a label this far from its own line. */
    private const val LAST_RESORT_OFFSET_PX: Float = 18f

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
        variants: Map<String, List<StmLabelVariant>> = emptyMap(),
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

        fun cands(id: String): List<Cand> =
            cache.getOrPut(id) {
                repairCandidates(
                    req = reqs.getValue(id),
                    offsets =
                        costs.repairOffsets ?: OFFSETS,
                )
            }

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

        // PAL Welle L: a label that is still open is tried narrower (and once with a slightly larger distance from its line).
        val unresolvedFinal = ArrayList<String>()
        val reason = LinkedHashMap<String, String>()
        val blockers = LinkedHashMap<String, List<String>>()
        for (u in stillOpen) {
            val c =
                if (variants[u].isNullOrEmpty()) {
                    null
                } else {
                    narrower(
                        u = u,
                        around = others(u),
                        reqs = reqs,
                        variants = variants,
                        base = base,
                        costs = costs,
                        budget = budget,
                    )
                }
            if (c != null) {
                repaired[u] = c
            } else {
                unresolvedFinal += u
            }
        }

        // Diagnostics only after every label had its chance, so they never take budget away from a repair. One pass per label.
        for (u in unresolvedFinal) {
            val (who, why) =
                diagnose(
                    req = reqs.getValue(u),
                    around = others(u),
                    cands = cands(u),
                    base = base,
                    costs = costs,
                    budget = budget,
                )
            blockers[u] = who
            reason[u] = why
        }

        val out = LinkedHashMap<String, PlacedLabel>()
        val chosen = LinkedHashMap<String, Int>()
        for ((id, p) in placed0) {
            val c = repaired[id]
            out[id] = if (c == null) p else PlacedLabel(rect = c.rect, onLine = false, clean = true, segment = c.segment)
            if (c != null && c.variant >= 0) chosen[id] = c.variant
        }
        return Result(placed = out, unresolved = unresolvedFinal, chosen = chosen, reason = reason, blockers = blockers)
    }

    /**
     * PAL Welle L: the first free position of [u] in one of its narrower [variants] (widest first), at the normal distance.
     * If none is free, one more round over the original size and all variants with the offset [LAST_RESORT_OFFSET_PX] and a
     * maximum distance of the same size. Every rule of [isViolation] stays; only the distance grows.
     */
    private fun narrower(
        u: String,
        around: Map<String, Rect>,
        reqs: Map<String, LabelRequest>,
        variants: Map<String, List<StmLabelVariant>>,
        base: ObstacleIndex,
        costs: LabelCostModel,
        budget: Budget,
    ): Cand? {
        val req = reqs.getValue(u)
        val vs = variants.getValue(u)

        fun search(
            variant: Int,
            size: dev.kuml.layout.Size,
            offsets: List<Float>,
            c: LabelCostModel,
        ): Cand? {
            if (budget.left <= 0) return null
            val r = req.copy(size = size)
            return repairCandidates(req = r, offsets = offsets)
                .firstOrNull {
                    budget.left > 0 &&
                        !isViolation(req = r, rect = it.rect, others = around, index = base, costs = c, budget = budget)
                }?.let { Cand(rect = it.rect, segment = it.segment, variant = variant) }
        }
        val base0 = costs.repairOffsets ?: OFFSETS
        for ((vi, v) in vs.withIndex()) search(variant = vi, size = v.size, offsets = base0, c = costs)?.let { return it }
        val wide = costs.copy(maxDistancePx = maxOf(costs.maxDistancePx, LAST_RESORT_OFFSET_PX))
        val offsets = base0 + LAST_RESORT_OFFSET_PX
        search(variant = -1, size = req.size, offsets = offsets, c = wide)?.let { return it }
        for ((vi, v) in vs.withIndex()) search(variant = vi, size = v.size, offsets = offsets, c = wide)?.let { return it }
        return null
    }

    /**
     * PAL Welle M: one pass over [cands] of [req]. Returns the obstacles that reject the positions as `kind id` (`kind` alone
     * without id), most frequent first, then alphabetical, and the short token of the rule that rejects most positions (ties:
     * alphabetical; `none` when no position is rejected). Both values come from the same pass and cannot contradict each other.
     */
    private fun diagnose(
        req: LabelRequest,
        around: Map<String, Rect>,
        cands: List<Cand>,
        base: ObstacleIndex,
        costs: LabelCostModel,
        budget: Budget,
    ): Pair<List<String>, String> {
        val byObstacle = HashMap<String, Int>()
        val byKind = HashMap<String, Int>()
        for (c in cands) {
            if (budget.left <= 0) break
            val b = violationBlocker(req = req, rect = c.rect, others = around, index = base, costs = costs, budget = budget) ?: continue
            val key = if (b.id == null) b.kind else "${b.kind} ${b.id}"
            byObstacle[key] = (byObstacle[key] ?: 0) + 1
            byKind[b.kind] = (byKind[b.kind] ?: 0) + 1
        }
        val who = byObstacle.entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
        val topKind = byKind.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()
        val why = topKind?.key ?: "none"
        return who to why
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
    ): Boolean = violationReason(req = req, rect = rect, others = others, index = index, costs = costs, budget = budget) != null

    /** PAL Welle M: the rule a position breaks ([kind], the short token) and the obstacle that breaks it ([id], null if it has none). */
    data class Blocker(
        val kind: String,
        val id: String?,
    )

    /**
     * The first rule [rect] breaks as a short fixed token (`label`, `node`, `foreign-edge`, `own-edge`, `own-far`,
     * `foreign-closer`), or null when it is an acceptable position. Same rules and order as [isViolation].
     */
    fun violationReason(
        req: LabelRequest,
        rect: Rect,
        others: Map<String, Rect>,
        index: ObstacleIndex,
        costs: LabelCostModel,
        budget: Budget? = null,
    ): String? = violationBlocker(req = req, rect = rect, others = others, index = index, costs = costs, budget = budget)?.kind

    /** Like [violationReason], but also names the obstacle (PAL Welle M): a label id, a node id or `frame`, an edge id. */
    fun violationBlocker(
        req: LabelRequest,
        rect: Rect,
        others: Map<String, Rect>,
        index: ObstacleIndex,
        costs: LabelCostModel,
        budget: Budget? = null,
    ): Blocker? {
        budget?.let { it.left -= index.size + others.size + 1 }
        for ((k, r) in others) if (tooClose(a = rect, b = r)) return Blocker(kind = "label", id = k)
        for (o in index.rects) {
            if (o.kind.isLabel) continue
            val other = if (costs.nodeClearance > 0f) o.core.inflated(costs.nodeClearance) else o.core
            if (other.intersects(rect)) return Blocker(kind = "node", id = o.ownerId)
        }
        for (a in req.avoid) if (a.intersects(rect)) return Blocker(kind = "node", id = null)
        val clearOwn = rect.inflated(costs.lineClearance)
        val clearForeign = rect.inflated(costs.foreignLineClearance)
        for (l in index.lines) {
            if (l.kind != ObstacleKind.EDGE && l.kind != ObstacleKind.FRAGMENT) continue
            val own = l.ownerId == req.ownerId
            if (own && !costs.ownCrossUnclean) continue
            val clear = if (own) clearOwn else clearForeign
            for (i in 0 until l.points.size - 1) {
                if (segmentIntersectsRect(a = l.points[i], b = l.points[i + 1], r = clear)) {
                    return Blocker(kind = if (own) "own-edge" else "foreign-edge", id = l.ownerId)
                }
            }
        }
        if (req.polyline.size >= 2) {
            val d = LabelPlacer.rectDistanceToPolyline(r = rect, pts = req.polyline)
            if (d > costs.maxDistancePx) return Blocker(kind = "own-far", id = null)
            val closer = LabelPlacer.foreignAsCloseId(r = rect, own = d, ownerId = req.ownerId, index = index)
            if (closer != null) return Blocker(kind = "foreign-closer", id = closer.ifEmpty { null })
        }
        return null
    }

    /**
     * The positions of [req] in the order they are tried: along the line from its middle outwards (the fractions 0.5, 0.35,
     * 0.65, 0.25, 0.75, then every [STEP_PX] up to 0.45 of the length either side of the middle); equally far from the middle
     * a position beside a horizontal segment comes first. At each position the nearest offset first, the right of the
     * direction of travel before the left. At most [MAX_CANDIDATES].
     */
    fun repairCandidates(
        req: LabelRequest,
        offsets: List<Float> = OFFSETS,
    ): List<Cand> {
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
            for (off in offsets) {
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
