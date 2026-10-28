package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.ACT_MAX_REGION_DEPTH
import dev.kuml.sysml2.ActDiagram
import dev.kuml.sysml2.ActionDefinition
import dev.kuml.sysml2.ActivityNodeKind
import dev.kuml.sysml2.ControlFlowUsage
import dev.kuml.sysml2.ExceptionHandlerUsage
import dev.kuml.sysml2.InterruptibleRegionDefinition
import dev.kuml.sysml2.ObjectFlowUsage
import dev.kuml.sysml2.PinDirection
import dev.kuml.sysml2.PinSide
import dev.kuml.sysml2.Sysml2Diagram
import dev.kuml.sysml2.Sysml2Model

/** Severity of an [ActIssue]. */
public enum class ActSeverity { Error, Warning }

/**
 * A finding of the activity diagram checker (wave C2).
 *
 * @property code stable numeric code (301-319), see [Sysml2ActChecker].
 * @property elementId id of the offending node, flow, region or handler.
 * @property hint how to fix it.
 */
public data class ActIssue(
    val code: Int,
    val severity: ActSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for the wave-C2 additions of SysML 2 activity diagrams.
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 301 | Error | send-signal / accept-event without a `signal` |
 * | 302 | Error | accept-time-event without a `timeExpression` |
 * | 303 | Error | `signal` / `timeExpression` / `exceptionType` longer than [MAX_TEXT] characters |
 * | 304 | Error | region cycle or nesting deeper than [ACT_MAX_REGION_DEPTH] |
 * | 305 | Error | exception handler protects its own handler node (handler == protected node) |
 * | 306 | Error | exception handler node is neither an action nor a call-behavior |
 * | 307 | Error | `sourcePin` is not an output pin / `targetPin` is not an input pin of the node |
 * | 308 | Error | more than [MAX_PINS_PER_SIDE] pins on one side of a node |
 * | 309 | Error | more than [MAX_HANDLERS] exception handlers on one protected node |
 * | 310 | Error | dangling reference (region of a node, parent region, handler endpoints) |
 * | 311 | Warning | `calledActivityId` does not resolve to a definition |
 * | 312 | Warning | interrupting flow without a region on its source node, or target in the same region |
 * | 313 | Warning | explicit `PinSide` on a pseudo node (it has no pins) |
 * | 314 | Warning | `showName` on a node that is not a control node |
 * | 315 | Warning | members of one region lie in different partitions |
 *
 * Accept-event and accept-time-event nodes without an incoming flow are valid and not reported.
 * All traversals are iterative and bounded.
 */
public object Sysml2ActChecker {
    public const val MAX_TEXT: Int = 80
    public const val MAX_PINS_PER_SIDE: Int = 8
    public const val MAX_HANDLERS: Int = 8

    private val CONTROL_KINDS =
        setOf(ActivityNodeKind.Decision, ActivityNodeKind.Merge, ActivityNodeKind.Fork, ActivityNodeKind.Join)
    private val PSEUDO_KINDS = setOf(ActivityNodeKind.Initial, ActivityNodeKind.Final, ActivityNodeKind.FlowFinal) + CONTROL_KINDS

    /** Checks [diagram] (or, when `null`, every activity diagram of [model]). Deterministic order. */
    public fun check(
        model: Sysml2Model,
        diagram: Sysml2Diagram? = null,
    ): List<ActIssue> {
        val diagrams = if (diagram != null) listOf(diagram) else model.diagrams
        val issues = ArrayList<ActIssue>()
        for (d in diagrams) if (d is ActDiagram) checkAct(model = model, d = d, issues = issues)
        return issues
    }

    private fun checkAct(
        model: Sysml2Model,
        d: ActDiagram,
        issues: MutableList<ActIssue>,
    ) {
        val defs = model.definitions.associateBy { it.id }
        val actions = model.definitions.filterIsInstance<ActionDefinition>().filter { it.id in d.elementIds }
        val actionById = actions.associateBy { it.id }
        val regions = model.definitions.filterIsInstance<InterruptibleRegionDefinition>().associateBy { it.id }

        for (a in actions) {
            if ((a.kind == ActivityNodeKind.SendSignal || a.kind == ActivityNodeKind.AcceptEvent) && a.signal.isNullOrBlank()) {
                issues += err(code = 301, id = a.id, message = "Node '${a.name}' has no signal.", hint = "Pass signal = \"…\".")
            }
            if (a.kind == ActivityNodeKind.AcceptTimeEvent && a.timeExpression.isNullOrBlank()) {
                issues +=
                    err(
                        code = 302,
                        id = a.id,
                        message = "Time event '${a.name}' has no time expression.",
                        hint = "Pass time = \"after 5 min\".",
                    )
            }
            for ((label, v) in listOf("signal" to a.signal, "timeExpression" to a.timeExpression)) {
                if (v != null && v.length > MAX_TEXT) {
                    issues +=
                        err(
                            code = 303,
                            id = a.id,
                            message = "$label of '${a.name}' exceeds $MAX_TEXT characters.",
                            hint = "Shorten the $label.",
                        )
                }
            }
            val calls = a.calledActivityId
            if (calls != null && calls !in defs) {
                issues +=
                    warn(
                        code = 311,
                        id = a.id,
                        message = "calledActivityId '$calls' of '${a.name}' does not resolve.",
                        hint = "Reference an existing definition.",
                    )
            }
            val rid = a.interruptibleRegionId
            if (rid != null && rid !in regions) {
                issues +=
                    err(
                        code = 310,
                        id = a.id,
                        message = "Region '$rid' of '${a.name}' does not exist.",
                        hint = "Declare it with interruptibleRegion(...).",
                    )
            }
            if (a.showName && a.kind !in CONTROL_KINDS) {
                issues +=
                    warn(
                        code = 314,
                        id = a.id,
                        message = "showName has no effect on '${a.name}'.",
                        hint = "Use showName on decision/merge/fork/join only.",
                    )
            }
            if (a.kind in PSEUDO_KINDS && a.pins.any { it.side != PinSide.Auto }) {
                issues += warn(code = 313, id = a.id, message = "Pseudo node '${a.name}' cannot carry pins.", hint = "Remove the pins.")
            }
            val perSide = a.pins.groupingBy { it.effectiveSide }.eachCount()
            for ((side, n) in perSide) {
                if (n > MAX_PINS_PER_SIDE) {
                    issues +=
                        err(
                            code = 308,
                            id = a.id,
                            message = "'${a.name}' has $n pins on side $side (max $MAX_PINS_PER_SIDE).",
                            hint = "Distribute pins over sides.",
                        )
                }
            }
        }

        // Regions: depth and cycles, bounded and iterative.
        for (r in regions.values.filter { it.id in regionIdsUsed(actions = actions, regions = regions) }) {
            var depth = 1
            var cur: InterruptibleRegionDefinition = r
            val seen = HashSet<String>()
            seen += r.id
            var broken = false
            while (true) {
                val pid = cur.parentRegionId ?: break
                val parent = regions[pid]
                if (parent == null) {
                    issues +=
                        err(
                            code = 310,
                            id = r.id,
                            message = "Parent region '$pid' of '${r.name}' does not exist.",
                            hint = "Fix parentRegionId.",
                        )
                    break
                }
                if (!seen.add(pid)) {
                    issues +=
                        err(code = 304, id = r.id, message = "Region '${r.name}' is part of a nesting cycle.", hint = "Remove the cycle.")
                    broken = true
                    break
                }
                depth++
                if (depth > ACT_MAX_REGION_DEPTH) {
                    issues +=
                        err(
                            code = 304,
                            id = r.id,
                            message = "Region '${r.name}' is nested deeper than $ACT_MAX_REGION_DEPTH levels.",
                            hint = "Flatten the regions.",
                        )
                    broken = true
                    break
                }
                cur = parent
            }
            if (broken) continue
            val partitions = actions.filter { it.interruptibleRegionId == r.id }.mapNotNull { it.partitionId }.toSet()
            if (partitions.size > 1) {
                issues +=
                    warn(
                        code = 315,
                        id = r.id,
                        message = "Region '${r.name}' spans ${partitions.size} partitions.",
                        hint = "Keep a region inside one partition.",
                    )
            }
        }

        // Flows.
        for (f in model.usages.filterIsInstance<ObjectFlowUsage>()) {
            val src = actionById[f.sourceNodeId]
            val tgt = actionById[f.targetNodeId]
            if (src == null || tgt == null) continue
            val sp = f.sourcePin
            if (sp != null && src.pins.none { it.name == sp && it.direction == PinDirection.Output }) {
                issues +=
                    err(
                        code = 307,
                        id = f.id,
                        message = "sourcePin '$sp' is not an output pin of '${src.name}'.",
                        hint = "Declare an output pin of that name.",
                    )
            }
            val tp = f.targetPin
            if (tp != null && tgt.pins.none { it.name == tp && it.direction == PinDirection.Input }) {
                issues +=
                    err(
                        code = 307,
                        id = f.id,
                        message = "targetPin '$tp' is not an input pin of '${tgt.name}'.",
                        hint = "Declare an input pin of that name.",
                    )
            }
            checkInterrupt(id = f.id, interrupts = f.interrupts, src = src, tgt = tgt, issues = issues)
        }
        for (f in model.usages.filterIsInstance<ControlFlowUsage>()) {
            val src = actionById[f.sourceNodeId] ?: continue
            val tgt = actionById[f.targetNodeId] ?: continue
            checkInterrupt(id = f.id, interrupts = f.interrupts, src = src, tgt = tgt, issues = issues)
        }

        // Exception handlers.
        val handlers = model.usages.filterIsInstance<ExceptionHandlerUsage>()
        for (h in handlers) {
            val prot = actionById[h.protectedNodeId]
            val hand = actionById[h.handlerNodeId]
            if (h.exceptionType.length > MAX_TEXT) {
                issues +=
                    err(
                        code = 303,
                        id = h.id,
                        message = "exceptionType exceeds $MAX_TEXT characters.",
                        hint = "Shorten the exception type.",
                    )
            }
            if (prot == null || hand == null) {
                if (h.protectedNodeId in d.elementIds || h.handlerNodeId in d.elementIds) {
                    issues +=
                        err(
                            code = 310,
                            id = h.id,
                            message = "Exception handler references an unknown node.",
                            hint = "Fix protectedNodeId / handlerNodeId.",
                        )
                }
                continue
            }
            if (prot.id == hand.id) {
                issues +=
                    err(code = 305, id = h.id, message = "Handler '${hand.name}' protects itself.", hint = "Use a separate handler node.")
            }
            if (hand.kind != ActivityNodeKind.Action && hand.kind != ActivityNodeKind.CallBehavior) {
                issues +=
                    err(
                        code = 306,
                        id = h.id,
                        message = "Handler '${hand.name}' must be an action or call-behavior.",
                        hint = "Use an action as handler.",
                    )
            }
            val hp = h.handlerPin
            if (hp != null && hand.pins.none { it.name == hp && it.direction == PinDirection.Input }) {
                issues +=
                    err(
                        code = 307,
                        id = h.id,
                        message = "handlerPin '$hp' is not an input pin of '${hand.name}'.",
                        hint = "Declare an input pin of that name.",
                    )
            }
        }
        for ((pid, n) in handlers.groupingBy { it.protectedNodeId }.eachCount()) {
            if (n > MAX_HANDLERS && pid in actionById) {
                issues +=
                    err(
                        code = 309,
                        id = pid,
                        message = "Node '$pid' has $n exception handlers (max $MAX_HANDLERS).",
                        hint = "Reduce the number of handlers.",
                    )
            }
        }
    }

    private fun regionIdsUsed(
        actions: List<ActionDefinition>,
        regions: Map<String, InterruptibleRegionDefinition>,
    ): Set<String> {
        val used = LinkedHashSet<String>()
        for (a in actions) a.interruptibleRegionId?.takeIf { it in regions }?.let { used += it }
        // include ancestors (bounded)
        var frontier = used.toList()
        var rounds = 0
        while (frontier.isNotEmpty() && rounds < ACT_MAX_REGION_DEPTH + 2) {
            frontier = frontier.mapNotNull { regions[it]?.parentRegionId }.filter { it in regions && used.add(it) }
            rounds++
        }
        return used
    }

    private fun checkInterrupt(
        id: String,
        interrupts: Boolean,
        src: ActionDefinition,
        tgt: ActionDefinition,
        issues: MutableList<ActIssue>,
    ) {
        if (!interrupts) return
        val sr = src.interruptibleRegionId
        if (sr == null || sr == tgt.interruptibleRegionId) {
            issues +=
                warn(
                    code = 312,
                    id = id,
                    message = "Interrupting flow does not leave a region.",
                    hint = "Source must be inside a region, target outside.",
                )
        }
    }

    private fun err(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = ActIssue(code = code, severity = ActSeverity.Error, elementId = id, message = message, hint = hint)

    private fun warn(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = ActIssue(code = code, severity = ActSeverity.Warning, elementId = id, message = message, hint = hint)
}
