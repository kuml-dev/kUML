package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Limits
import dev.kuml.sysml2.Sysml2Model

/** Severity of a [ParIssue]. */
public enum class ParSeverity { Error, Warning }

/** A finding of the parametric structure checker (wave C4). */
public data class ParIssue(
    val code: Int,
    val severity: ParSeverity,
    val elementId: String,
    val message: String,
    val hint: String,
)

/**
 * Structural checker for parametric diagrams: nested constraint definitions and pin bindings.
 *
 * | Code | Severity | Rule |
 * |------|----------|------|
 * | 437 | Error | nesting deeper than [Sysml2Limits.CONSTRAINT_DEPTH] (the root counts as level 1) |
 * | 438 | Error | a cycle in the `ownerConstraintId` chain |
 * | 439 | Error | `ownerConstraintId` refers to an unknown constraint definition |
 * | 440 | Error | more than [Sysml2Limits.CONSTRAINT_PARAMETERS] parameters on one constraint |
 * | 441 | Warning | a binding endpoint addresses a constraint, but the rest is not one of its parameters |
 *
 * Chains are walked iteratively with a hard step bound, so hostile models cannot make the checker loop.
 */
public object Sysml2ParChecker {
    public fun check(
        model: Sysml2Model,
        diagram: ParDiagram? = null,
    ): List<ParIssue> {
        val visible = diagram?.elementIds?.toSet()
        val all = model.definitions.filterIsInstance<ConstraintDefinition>()
        val byId = LinkedHashMap<String, ConstraintDefinition>()
        all.forEach { if (it.id !in byId) byId[it.id] = it }
        val constraints = all.filter { visible == null || it.id in visible }
        val issues = mutableListOf<ParIssue>()
        for (c in constraints) {
            if (c.parameters.size > Sysml2Limits.CONSTRAINT_PARAMETERS) {
                issues +=
                    ParIssue(
                        code = 440,
                        severity = ParSeverity.Error,
                        elementId = c.id,
                        message =
                            "Constraint '${c.name}' has ${c.parameters.size} parameters; " +
                                "the limit is ${Sysml2Limits.CONSTRAINT_PARAMETERS}.",
                        hint = "Split the constraint into smaller nested constraints.",
                    )
            }
            val owner = c.ownerConstraintId ?: continue
            if (owner !in byId) {
                issues +=
                    ParIssue(
                        code = 439,
                        severity = ParSeverity.Error,
                        elementId = c.id,
                        message = "Constraint '${c.name}' is nested in '$owner', which is not a constraint definition.",
                        hint = "Pass an existing constraint definition as parent.",
                    )
                continue
            }
            val depthOrCycle = nestingDepth(byId = byId, start = c)
            when {
                depthOrCycle < 0 ->
                    issues +=
                        ParIssue(
                            code = 438,
                            severity = ParSeverity.Error,
                            elementId = c.id,
                            message = "Constraint '${c.name}' is part of an ownership cycle.",
                            hint = "A constraint cannot be nested, directly or indirectly, in itself.",
                        )
                depthOrCycle > Sysml2Limits.CONSTRAINT_DEPTH ->
                    issues +=
                        ParIssue(
                            code = 437,
                            severity = ParSeverity.Error,
                            elementId = c.id,
                            message =
                                "Constraint '${c.name}' is nested $depthOrCycle levels deep; " +
                                    "the limit is ${Sysml2Limits.CONSTRAINT_DEPTH}.",
                            hint = "Flatten the hierarchy (two levels are recommended).",
                        )
            }
        }
        val visibleIds = constraints.map { it.id }.toSet()
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            for (end in listOf(b.sourceEndId, b.targetEndId)) {
                val owner = visibleIds.filter { end.startsWith("$it::") }.maxByOrNull { it.length } ?: continue
                val rest = end.removePrefix("$owner::")
                val params = byId.getValue(owner).parameters
                if (params.none { it.name == rest }) {
                    issues +=
                        ParIssue(
                            code = 441,
                            severity = ParSeverity.Warning,
                            elementId = b.id,
                            message =
                                "Binding endpoint '$end' does not name a parameter of constraint '$owner'; " +
                                    "the edge ends at the box centre.",
                            hint = "Use one of: ${params.joinToString {
                                it.name
                            }.ifEmpty { "(none declared)" }}. The pin() helper checks this at build time.",
                        )
                }
            }
        }
        return issues
    }

    /** Depth of [start] in its owner chain (root = 1), or -1 when the chain has a cycle or exceeds the step bound. */
    private fun nestingDepth(
        byId: Map<String, ConstraintDefinition>,
        start: ConstraintDefinition,
    ): Int {
        val seen = HashSet<String>()
        var cur: ConstraintDefinition? = start
        var depth = 0
        while (cur != null) {
            if (!seen.add(cur.id)) return -1
            depth++
            if (depth > MAX_CHAIN_STEPS) return depth
            cur = cur.ownerConstraintId?.let { byId[it] }
        }
        return depth
    }

    private const val MAX_CHAIN_STEPS: Int = 64
}
