package dev.kuml.layout.bridge.label

import dev.kuml.layout.Point
import dev.kuml.layout.Rect
import dev.kuml.layout.bridge.note.ObstacleIndex
import dev.kuml.layout.bridge.note.intersects
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * PAL Welle Y2: local per-label repair of STM transition labels that sit closer than 6 px to their own line (E1).
 *
 * Runs after the whole-diagram X1 gate of [Sysml2DiagramLabelPlan.planStmLabels]. For each such label only that label
 * is moved to the first position (4 px raster along the line, offsets 6/8/10/14) that breaks no hard rule of
 * [StmLabelRepair.isViolation] and keeps 6 to 16 px from its own line. All other labels stay where they are (no rip-up, no
 * wrap change, no font change). The result is kept only when it leaves fewer labels outside the band and nothing gets
 * worse; otherwise the incoming plan is returned (same instance). Deterministic: fixed order, fixed candidate lists.
 */
internal object StmEdgeGapRepair {
    private val logger = KotlinLogging.logger {}

    internal const val Y2_STEP_PX: Float = 4f
    internal const val Y2_MAX_FRACTION: Float = 0.45f
    internal const val Y2_MAX_STEPS: Int = 48
    internal const val Y2_MAX_CANDIDATES: Int = 800
    private const val PER_LABEL_BUDGET: Long = 400_000L
    private const val MAX_PASSES: Int = 3
    private const val OVERALL_FACTOR: Long = 4L
    private const val DIAGNOSE_BUDGET: Long = 400_000L
    private const val MAX_LOG_BLOCKERS: Int = 3
    private const val MAX_LOG_ID: Int = 120

    /** Labels of [labels] closer than the band minimum to their own line (E1), nearest first, ties by id. */
    internal fun e1Labels(
        labels: Map<String, PlacedLabel>,
        polys: Map<String, List<Point>>,
    ): List<Pair<String, Float>> =
        labels
            .mapNotNull { (id, p) ->
                val pts = polys[id] ?: return@mapNotNull null
                if (pts.size < 2) return@mapNotNull null
                val d = LabelPlacer.rectDistanceToPolyline(r = p.rect, pts = pts)
                if (d < Sysml2DiagramLabelPlan.EDGE_GAP_MIN_PX - Sysml2DiagramLabelPlan.EDGE_GAP_EPS_PX) id to d else null
            }.sortedWith(compareBy<Pair<String, Float>>({ it.second }, { it.first }))

    fun repair(
        plan: Sysml2LabelPlanResult,
        requests: List<LabelRequest>,
        base: ObstacleIndex,
        polys: Map<String, List<Point>>,
        costs: LabelCostModel = Sysml2DiagramLabelPlan.STM_COSTS_Y2,
    ): Sysml2LabelPlanResult {
        val targets = e1Labels(labels = plan.labels, polys = polys)
        if (targets.isEmpty()) return plan
        val offsets = costs.repairOffsets ?: return plan
        val reqs = requests.associateBy { it.id }
        val rects = LinkedHashMap<String, Rect>()
        for ((id, p) in plan.labels.entries.sortedBy { it.key }) rects[id] = p.rect
        // Own budgets (DoS guard): one per label, so a crowded label cannot starve the next, and one overall cap.
        val perLabel = costs.perRequestWorkBudget ?: PER_LABEL_BUDGET
        var overall = costs.totalWorkBudget * OVERALL_FACTOR
        val moved = LinkedHashMap<String, PlacedLabel>()
        var pending = targets
        var failed: List<Pair<String, Float>> = emptyList()
        // A label that found no spot may get one after a later label has moved away from it: repeat until nothing moves.
        var pass = 0
        while (pass++ < MAX_PASSES) {
            val failedNow = ArrayList<Pair<String, Float>>()
            for ((id, d0) in pending) {
                val base0 = reqs[id]
                val placed = plan.labels[id]
                val pts = polys[id]
                if (base0 == null || placed == null || pts == null) {
                    failedNow += id to d0
                    continue
                }
                val req = base0.copy(size = placed.rect.size)
                val cands =
                    StmLabelRepair.repairCandidates(
                        req = req,
                        offsets = offsets,
                        stepPx = Y2_STEP_PX,
                        maxFraction = Y2_MAX_FRACTION,
                        maxSteps = Y2_MAX_STEPS,
                        maxCandidates = Y2_MAX_CANDIDATES,
                    )
                val others = rects.filterKeys { it != id }
                val budget = StmLabelRepair.Budget(left = minOf(perLabel, overall))
                var found: StmLabelRepair.Cand? = null
                for (c in cands) {
                    if (budget.left <= 0L) break
                    // The cheap band test first: only positions inside the 6 to 16 px band cost a full obstacle check.
                    val d = LabelPlacer.rectDistanceToPolyline(r = c.rect, pts = pts)
                    if (d < Sysml2DiagramLabelPlan.EDGE_GAP_MIN_PX || d > Sysml2DiagramLabelPlan.EDGE_GAP_MAX_PX) continue
                    if (StmLabelRepair.isViolation(
                            req = req,
                            rect = c.rect,
                            others = others,
                            index = base,
                            costs = costs,
                            budget = budget,
                        )
                    ) {
                        continue
                    }
                    found = c
                    break
                }
                overall -= perLabel - budget.left
                if (found == null) {
                    failedNow += id to d0
                    continue
                }
                rects[id] = found.rect
                moved[id] = placed.copy(rect = found.rect, onLine = false, clean = true, segment = found.segment)
            }
            failed = failedNow
            if (failedNow.isEmpty() || failedNow.size == pending.size || overall <= 0L) break
            pending = failedNow
        }
        for ((id, d0) in failed) {
            diagnose(
                id = id,
                d0 = d0,
                reqs = reqs,
                plan = plan,
                rects = rects,
                base = base,
                polys = polys,
                costs = costs,
            )
        }
        if (moved.isEmpty()) return plan
        val labels = LinkedHashMap(plan.labels)
        labels.putAll(moved)
        val repaired =
            plan.copy(
                labels = labels,
                unresolved = plan.unresolved - moved.keys,
                unresolvedReason = plan.unresolvedReason - moved.keys,
                unresolvedBlockers = plan.unresolvedBlockers - moved.keys,
            )
        val ok =
            Sysml2DiagramLabelPlan.edgeGapViolations(labels = repaired.labels, polys = polys) <
                Sysml2DiagramLabelPlan.edgeGapViolations(labels = plan.labels, polys = polys) &&
                plan.unresolved.toSet().containsAll(repaired.unresolved) &&
                plan.labels.none { (id, p) -> p.clean && repaired.labels[id]?.clean == false } &&
                repaired.labels.count { !it.value.clean } <= plan.labels.count { !it.value.clean } &&
                noNewOverlap(repaired = repaired.labels, moved = moved.keys)
        return if (ok) repaired else plan
    }

    private fun noNewOverlap(
        repaired: Map<String, PlacedLabel>,
        moved: Set<String>,
    ): Boolean {
        for (m in moved) {
            val a = repaired[m]?.rect ?: continue
            for ((id, p) in repaired) if (id != m && a.intersects(p.rect)) return false
        }
        return true
    }

    private fun diagnose(
        id: String,
        d0: Float,
        reqs: Map<String, LabelRequest>,
        plan: Sysml2LabelPlanResult,
        rects: Map<String, Rect>,
        base: ObstacleIndex,
        polys: Map<String, List<Point>>,
        costs: LabelCostModel,
    ) {
        if (!logger.isDebugEnabled()) return
        val req = reqs[id]?.copy(size = plan.labels[id]?.rect?.size ?: return) ?: return
        val pts = polys[id] ?: return
        val budget = StmLabelRepair.Budget(left = DIAGNOSE_BUDGET)
        val counts = LinkedHashMap<String, Int>()
        val others = rects.filterKeys { it != id }
        val cands =
            StmLabelRepair.repairCandidates(
                req = req,
                offsets = costs.repairOffsets ?: return,
                stepPx = Y2_STEP_PX,
                maxFraction = Y2_MAX_FRACTION,
                maxSteps = Y2_MAX_STEPS,
                maxCandidates = Y2_MAX_CANDIDATES,
            )
        for (c in cands) {
            if (budget.left <= 0L) break
            val d = LabelPlacer.rectDistanceToPolyline(r = c.rect, pts = pts)
            val key =
                if (d < Sysml2DiagramLabelPlan.EDGE_GAP_MIN_PX) {
                    "own-near"
                } else if (d > Sysml2DiagramLabelPlan.EDGE_GAP_MAX_PX) {
                    "own-far"
                } else {
                    val b =
                        StmLabelRepair.violationBlocker(
                            req = req,
                            rect = c.rect,
                            others = others,
                            index = base,
                            costs = costs,
                            budget = budget,
                        )
                    if (b == null) "free" else "${b.kind} ${b.id.orEmpty()}".trim()
                }
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top =
            counts.entries
                .sortedWith(compareBy<Map.Entry<String, Int>>({ -it.value }, { it.key }))
                .take(MAX_LOG_BLOCKERS)
                .joinToString(", ") { "${Sysml2DiagramLabelPlan.logSafe(it.key).take(MAX_LOG_ID)} x${it.value}" }
        logger.debug { "PAL-Y2 edge-gap: label=${Sysml2DiagramLabelPlan.logSafe(id).take(MAX_LOG_ID)} d=$d0 blockers=[$top]" }
    }
}
