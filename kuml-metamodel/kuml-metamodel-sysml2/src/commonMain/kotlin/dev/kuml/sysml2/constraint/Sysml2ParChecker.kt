package dev.kuml.sysml2.constraint

import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.PartDefinition
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
 * | 442 | Warning | `include` of an `AttributeDefinition` in the diagram (no effect; the unit comes from `unitSymbol` on the parameter type) |
 * | 443 | Warning | a binding endpoint addresses a visible part (`<part>::<attr>`), but the part has no attribute of that name |
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
        issues += checkBindingEnds(model = model, visible = visible, constraints = constraints, byId = byId)
        if (diagram != null) {
            val attributeDefs =
                model.definitions
                    .filterIsInstance<AttributeDefinition>()
                    .map { it.id }
                    .toSet()
            for (id in diagram.elementIds.distinct().filter { it in attributeDefs }) {
                val shown = id.take(Sysml2Limits.TEXT)
                issues +=
                    ParIssue(
                        code = 442,
                        severity = ParSeverity.Warning,
                        elementId = id,
                        message =
                            "include of attributeDef '$shown' has no effect in a parametric diagram; " +
                                "units are shown via unitSymbol on parameters",
                        hint =
                            "Include a value property (attribute usage) or the owning part definition instead; " +
                                "set unitSymbol on the attributeDef to show its unit at every parameter typed with it.",
                    )
            }
        }
        return issues
    }

    /** 441 (unknown constraint parameter) and 443 (unknown part attribute) for every binding endpoint. */
    private fun checkBindingEnds(
        model: Sysml2Model,
        visible: Set<String>?,
        constraints: List<ConstraintDefinition>,
        byId: Map<String, ConstraintDefinition>,
    ): List<ParIssue> {
        val issues = mutableListOf<ParIssue>()
        val visibleIds = constraints.map { it.id }.toSet()
        val partById = LinkedHashMap<String, PartDefinition>()
        model.definitions.filterIsInstance<PartDefinition>().forEach { if (it.id !in partById) partById[it.id] = it }
        val visibleParts = partById.filterKeys { visible == null || it in visible }
        for (b in model.usages.filterIsInstance<BindingConnectorUsage>()) {
            for (end in listOf(b.sourceEndId, b.targetEndId)) {
                // Longest prefix over constraints AND parts, as the renderer resolves it; a constraint wins a tie.
                val owner =
                    (visibleIds + visibleParts.keys)
                        .filter { end.startsWith("$it::") }
                        .maxWithOrNull(compareBy<String> { it.length }.thenBy { if (it in visibleIds) 1 else 0 })
                        ?: continue
                if (owner in visibleIds) {
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
                } else {
                    unknownAttributeIssue(
                        model = model,
                        bindingId = b.id,
                        end = end,
                        part = visibleParts.getValue(owner),
                        partById = partById,
                    )?.let { issues += it }
                }
            }
        }
        return issues
    }

    private fun unknownAttributeIssue(
        model: Sysml2Model,
        bindingId: String,
        end: String,
        part: PartDefinition,
        partById: Map<String, PartDefinition>,
    ): ParIssue? {
        val rest = end.removePrefix("${part.id}::")
        // Nested paths are not resolvable in this iteration.
        if (rest.isEmpty() || "::" in rest || '.' in rest) return null
        val known = knownAttributeNames(model = model, part = part, partById = partById) ?: return null
        if (rest in known) return null
        return ParIssue(
            code = 443,
            severity = ParSeverity.Warning,
            elementId = bindingId,
            message =
                "Binding endpoint '${end.take(
                    Sysml2Limits.TEXT,
                )}' does not name an attribute of part '${part.name.take(Sysml2Limits.TEXT)}'; " +
                    "attribute '${rest.take(Sysml2Limits.TEXT)}' is unknown and the edge ends at the box border.",
            hint = "Use one of: ${known.take(MAX_HINT_NAMES).joinToString().ifEmpty { "(none declared)" }}.",
        )
    }

    /**
     * Names of the features of [part] (every feature counts, as the renderer lists every feature as a row), attribute usages
     * keyed under the part, and the features of its generalizations. `null` when a generalization cannot be resolved.
     */
    private fun knownAttributeNames(
        model: Sysml2Model,
        part: PartDefinition,
        partById: Map<String, PartDefinition>,
    ): Set<String>? {
        val names = LinkedHashSet<String>()
        val prefix = "${part.id}::"
        model.usages
            .filterIsInstance<AttributeUsage>()
            .filter { it.id.startsWith(prefix) }
            .forEach { names += it.id.removePrefix(prefix) }
        val seen = HashSet<String>()
        var cur: PartDefinition? = part
        var steps = 0
        while (cur != null) {
            if (!seen.add(cur.id) || ++steps > MAX_CHAIN_STEPS) return names
            cur.features.forEach { names += it.name }
            val generalId = cur.specializations.firstOrNull { it.specificId == cur!!.id }?.generalId ?: break
            cur = partById[generalId] ?: return null
        }
        return names
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
    private const val MAX_HINT_NAMES: Int = 20
}
