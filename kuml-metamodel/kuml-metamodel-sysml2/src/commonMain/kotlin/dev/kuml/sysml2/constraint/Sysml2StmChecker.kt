package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.STM_MAX_DEPTH
import dev.kuml.sysml2.STM_MAX_NAME_LEN
import dev.kuml.sysml2.STM_MAX_REGIONS
import dev.kuml.sysml2.STM_MAX_STATES
import dev.kuml.sysml2.StateDefinition
import dev.kuml.sysml2.StateKind
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.TransitionUsage
import dev.kuml.sysml2.ancestorsOf
import dev.kuml.sysml2.stateIndex
import dev.kuml.sysml2.visibleStateIds

/** Severity of an [StmIssue]. */
public enum class StmSeverity { Error, Warning }

/**
 * A structural finding of the SysML 2 state-machine checker.
 *
 * @property code stable numeric code (101-123), see [Sysml2StmChecker].
 * @property elementId id of the offending state / region / transition.
 * @property hint how to fix it.
 */
public data class StmIssue(
    val code: Int,
    val severity: StmSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for SysML 2 state machines (wave A1: composite states,
 * orthogonal regions, pseudo-states, history).
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 101 | Error | Initial pseudo-state has an incoming transition |
 * | 102 | Error | Final pseudo-state has an outgoing transition |
 * | 103 | Error | Fork targets are not in pairwise different regions |
 * | 104 | Error | Join sources are not in pairwise different regions |
 * | 105 | Error | Fork needs exactly one incoming and at least two outgoing transitions |
 * | 106 | Error | Join needs at least two incoming and exactly one outgoing transition |
 * | 107 | Error | Choice has no outgoing transition |
 * | 108 | Warning | Choice has more than one outgoing transition but no guard-less (else) branch |
 * | 109 | Error | Junction needs at least one incoming and one outgoing transition |
 * | 110 | Error | History pseudo-state without a parent composite |
 * | 111 | Error | More than one history pseudo-state of the same kind in one region |
 * | 112 | Error | History pseudo-state has more than one default (outgoing) transition |
 * | 113 | Error | Direct transition between two regions of the same composite |
 * | 114 | Error | More than one initial pseudo-state in one region of a composite |
 * | 115 | Warning | Transition between a composite and one of its own descendants |
 * | 116 | Error | `parentStateId` does not resolve to a state |
 * | 117 | Error | `regionId` does not resolve to a region of the parent |
 * | 118 | Error | Parent of a state is a pseudo-state |
 * | 119 | Error | Duplicate region id within one composite |
 * | 120 | Error | Nesting deeper than [STM_MAX_DEPTH] (or a parent cycle) |
 * | 121 | Error | More than [STM_MAX_REGIONS] regions in one composite |
 * | 122 | Error | More than [STM_MAX_STATES] states |
 * | 123 | Error | State or region name longer than [STM_MAX_NAME_LEN] characters |
 *
 * The limit rules (120-123) run first; when one fires only the limit findings
 * are reported and the deeper analysis is skipped (DoS guard). Everything is
 * iterative and bounded.
 */
public object Sysml2StmChecker {
    public fun check(
        model: Sysml2Model,
        diagram: StmDiagram? = null,
    ): List<StmIssue> {
        val index = model.stateIndex()
        val scope: Set<String> = diagram?.visibleStateIds(model) ?: index.keys
        val states = scope.mapNotNull { index[it] }

        val limits = checkLimits(model = model, states = states, index = index)
        if (limits.isNotEmpty()) return limits

        val issues = ArrayList<StmIssue>()
        checkHierarchy(states = states, index = index, issues = issues)

        val transitions =
            model.usages
                .filterIsInstance<TransitionUsage>()
                .filter { it.sourceStateId in scope && it.targetStateId in scope }
        val outgoing = transitions.groupBy { it.sourceStateId }
        val incoming = transitions.groupBy { it.targetStateId }

        for (s in states) {
            val ins = incoming[s.id].orEmpty()
            val outs = outgoing[s.id].orEmpty()
            when (s.effectiveKind) {
                StateKind.Initial ->
                    if (ins.isNotEmpty()) {
                        issues +=
                            err(
                                code = 101,
                                id = s.id,
                                message = "Initial state '${s.name}' has an incoming transition.",
                                hint = "Remove the transition into the initial state.",
                            )
                    }
                StateKind.Final ->
                    if (outs.isNotEmpty()) {
                        issues +=
                            err(
                                code = 102,
                                id = s.id,
                                message = "Final state '${s.name}' has an outgoing transition.",
                                hint = "Remove the transition out of the final state.",
                            )
                    }
                StateKind.Fork -> {
                    if (ins.size != 1 || outs.size < 2) {
                        issues +=
                            err(
                                code = 105,
                                id = s.id,
                                message = "Fork '${s.name}' has ${ins.size} incoming and ${outs.size} outgoing transitions.",
                                hint = "A fork needs exactly one incoming and at least two outgoing transitions.",
                            )
                    }
                    val keys = outs.mapNotNull { index[it.targetStateId] }.map { regionKey(it) }
                    if (keys.size >= 2 && keys.toSet().size != keys.size) {
                        issues +=
                            err(
                                code = 103,
                                id = s.id,
                                message = "Fork '${s.name}' has targets in the same region.",
                                hint = "Target one state per orthogonal region.",
                            )
                    }
                }
                StateKind.Join -> {
                    if (ins.size < 2 || outs.size != 1) {
                        issues +=
                            err(
                                code = 106,
                                id = s.id,
                                message = "Join '${s.name}' has ${ins.size} incoming and ${outs.size} outgoing transitions.",
                                hint = "A join needs at least two incoming and exactly one outgoing transition.",
                            )
                    }
                    val keys = ins.mapNotNull { index[it.sourceStateId] }.map { regionKey(it) }
                    if (keys.size >= 2 && keys.toSet().size != keys.size) {
                        issues +=
                            err(
                                code = 104,
                                id = s.id,
                                message = "Join '${s.name}' has sources in the same region.",
                                hint = "Join one state per orthogonal region.",
                            )
                    }
                }
                StateKind.Choice -> {
                    if (outs.isEmpty()) {
                        issues +=
                            err(
                                code = 107,
                                id = s.id,
                                message = "Choice '${s.name}' has no outgoing transition.",
                                hint = "Add at least one outgoing transition.",
                            )
                    } else if (outs.size > 1 && outs.none { it.guard.isNullOrBlank() }) {
                        issues +=
                            warn(
                                code = 108,
                                id = s.id,
                                message = "Choice '${s.name}' has no guard-less (else) branch.",
                                hint = "Leave one outgoing transition without a guard as the else branch.",
                            )
                    }
                }
                StateKind.Junction ->
                    if (ins.isEmpty() || outs.isEmpty()) {
                        issues +=
                            err(
                                code = 109,
                                id = s.id,
                                message = "Junction '${s.name}' needs incoming and outgoing transitions.",
                                hint = "Connect the junction on both sides.",
                            )
                    }
                StateKind.ShallowHistory, StateKind.DeepHistory ->
                    if (outs.size > 1) {
                        issues +=
                            err(
                                code = 112,
                                id = s.id,
                                message = "History '${s.name}' has ${outs.size} default transitions.",
                                hint = "Keep at most one outgoing (default) transition.",
                            )
                    }
                StateKind.State -> Unit
            }
        }

        for ((key, group) in states
            .filter {
                it.isHistory && it.parentStateId != null
            }.groupBy { Triple(it.parentStateId, it.regionId, it.effectiveKind) }) {
            if (group.size > 1) {
                issues +=
                    err(
                        code = 111,
                        id = group[1].id,
                        message = "Region of '${key.first}' has ${group.size} history states of kind ${key.third}.",
                        hint = "Keep one history state per kind and region.",
                    )
            }
        }
        for ((key, group) in states.filter { it.effectiveKind == StateKind.Initial && it.parentStateId != null }.groupBy {
            it.parentStateId to
                it.regionId
        }) {
            if (group.size > 1) {
                issues +=
                    err(
                        code = 114,
                        id = group[1].id,
                        message = "Region of '${key.first}' has ${group.size} initial states.",
                        hint = "Keep a single initial state per region.",
                    )
            }
        }

        for (t in transitions) {
            val src = index[t.sourceStateId] ?: continue
            val tgt = index[t.targetStateId] ?: continue
            if (tgt.id in model.ancestorsOf(id = src.id, maxDepth = STM_MAX_DEPTH, index = index) ||
                src.id in model.ancestorsOf(id = tgt.id, maxDepth = STM_MAX_DEPTH, index = index)
            ) {
                issues +=
                    warn(
                        code = 115,
                        id = t.id,
                        message = "Transition '${t.id}' connects a composite with one of its own descendants.",
                        hint = "Edges to a composite and its own descendants attach to the frame; prefer entry/exit pseudo-states.",
                    )
            }
            val fj = setOf(StateKind.Fork, StateKind.Join)
            if (src.effectiveKind !in fj && tgt.effectiveKind !in fj && crossesRegions(model = model, a = src, b = tgt, index = index)) {
                issues +=
                    err(
                        code = 113,
                        id = t.id,
                        message = "Transition '${t.id}' crosses from one region of a composite into another.",
                        hint = "Use a fork or join to enter or leave orthogonal regions.",
                    )
            }
        }
        return issues
    }

    /**
     * Only the limit rules (codes 120-123) for the states visible in [diagram]
     * (or all states when `null`). Cheap guard for renderers and layout code.
     */
    public fun checkLimits(
        model: Sysml2Model,
        diagram: StmDiagram? = null,
    ): List<StmIssue> {
        val index = model.stateIndex()
        val scope: Set<String> = diagram?.visibleStateIds(model) ?: index.keys
        return checkLimits(model = model, states = scope.mapNotNull { index[it] }, index = index)
    }

    private fun checkLimits(
        model: Sysml2Model,
        states: List<StateDefinition>,
        index: Map<String, StateDefinition>,
    ): List<StmIssue> {
        val out = ArrayList<StmIssue>()
        if (states.size > STM_MAX_STATES) {
            out +=
                err(
                    code = 122,
                    id = states.first().id,
                    message = "State machine has ${states.size} states (limit $STM_MAX_STATES).",
                    hint = "Split the state machine.",
                )
            return out
        }
        for (s in states) {
            if (s.name.length > STM_MAX_NAME_LEN) {
                out +=
                    err(
                        code = 123,
                        id = s.id,
                        message = "State name has ${s.name.length} characters (limit $STM_MAX_NAME_LEN).",
                        hint = "Shorten the name.",
                    )
            }
            for (r in s.regions) {
                if ((r.name?.length ?: 0) > STM_MAX_NAME_LEN) {
                    out +=
                        err(
                            code = 123,
                            id = r.id,
                            message = "Region name has ${r.name?.length} characters (limit $STM_MAX_NAME_LEN).",
                            hint = "Shorten the name.",
                        )
                }
            }
            if (s.regions.size > STM_MAX_REGIONS) {
                out +=
                    err(
                        code = 121,
                        id = s.id,
                        message = "State '${s.name}' has ${s.regions.size} regions (limit $STM_MAX_REGIONS).",
                        hint = "Reduce the number of orthogonal regions.",
                    )
            }
            // Walk the parent chain with a hard step bound; detect cycles explicitly.
            var cur: String? = s.parentStateId
            var steps = 0
            val seen = HashSet<String>()
            seen += s.id
            while (cur != null) {
                if (!seen.add(cur)) {
                    out +=
                        err(
                            code = 120,
                            id = s.id,
                            message = "State '${s.name}' is part of a parent cycle.",
                            hint = "Break the cycle in parentStateId.",
                        )
                    break
                }
                steps++
                if (steps > STM_MAX_DEPTH) {
                    out +=
                        err(
                            code = 120,
                            id = s.id,
                            message = "State '${s.name}' is nested deeper than $STM_MAX_DEPTH levels.",
                            hint = "Flatten the hierarchy.",
                        )
                    break
                }
                cur = index[cur]?.parentStateId
            }
        }
        return out
    }

    private fun checkHierarchy(
        states: List<StateDefinition>,
        index: Map<String, StateDefinition>,
        issues: MutableList<StmIssue>,
    ) {
        for (s in states) {
            val pid = s.parentStateId
            if (pid == null) {
                if (s.regionId != null) {
                    issues +=
                        err(
                            code = 117,
                            id = s.id,
                            message = "State '${s.name}' names region '${s.regionId}' but has no parent.",
                            hint = "Set a parent or drop the region.",
                        )
                }
                if (s.isHistory) {
                    issues +=
                        err(
                            code = 110,
                            id = s.id,
                            message = "History state '${s.name}' has no parent composite.",
                            hint = "Declare it with parent = <composite state>.",
                        )
                }
            } else {
                val parent = index[pid]
                if (parent == null) {
                    issues +=
                        err(
                            code = 116,
                            id = s.id,
                            message = "Parent '$pid' of '${s.name}' is not a known state.",
                            hint = "Declare the parent state first or fix the id.",
                        )
                } else {
                    if (parent.isPseudo) {
                        issues +=
                            err(
                                code = 118,
                                id = s.id,
                                message = "Parent '${parent.name}' of '${s.name}' is a pseudo-state.",
                                hint = "Only regular states can be composites.",
                            )
                    }
                    val rid = s.regionId
                    if (rid != null && parent.regions.none { it.id == rid }) {
                        issues +=
                            err(
                                code = 117,
                                id = s.id,
                                message = "Region '$rid' is not declared on '${parent.name}'.",
                                hint = "Declare it with region(${parent.name}, ...).",
                            )
                    }
                }
            }
            val ids = s.regions.map { it.id }
            if (ids.toSet().size != ids.size) {
                issues +=
                    err(
                        code = 119,
                        id = s.id,
                        message = "State '${s.name}' declares duplicate region ids.",
                        hint = "Give every region a unique id.",
                    )
            }
            for (r in s.regions) {
                if (r.id in index) {
                    issues +=
                        err(
                            code = 119,
                            id = r.id,
                            message = "Region id '${r.id}' collides with a state id.",
                            hint = "Choose a region id that is not a state id.",
                        )
                }
            }
        }
    }

    private fun regionKey(s: StateDefinition): Pair<String?, String?> = s.parentStateId to s.regionId

    /** `true` when [a] and [b] lie below the same composite but in different regions of it. */
    private fun crossesRegions(
        model: Sysml2Model,
        a: StateDefinition,
        b: StateDefinition,
        index: Map<String, StateDefinition>,
    ): Boolean {
        val pathA = (model.ancestorsOf(id = a.id, maxDepth = STM_MAX_DEPTH, index = index).asReversed() + a.id)
        val pathB = (model.ancestorsOf(id = b.id, maxDepth = STM_MAX_DEPTH, index = index).asReversed() + b.id)
        var i = 0
        while (i < pathA.size && i < pathB.size && pathA[i] == pathB[i]) i++
        if (i == 0 || i >= pathA.size || i >= pathB.size) return false
        val childA = index[pathA[i]] ?: return false
        val childB = index[pathB[i]] ?: return false
        return childA.parentStateId == childB.parentStateId && childA.regionId != childB.regionId
    }

    private fun err(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = StmIssue(code = code, severity = StmSeverity.Error, elementId = id, message = message, hint = hint)

    private fun warn(
        code: Int,
        id: String,
        message: String,
        hint: String,
    ) = StmIssue(code = code, severity = StmSeverity.Warning, elementId = id, message = message, hint = hint)
}
