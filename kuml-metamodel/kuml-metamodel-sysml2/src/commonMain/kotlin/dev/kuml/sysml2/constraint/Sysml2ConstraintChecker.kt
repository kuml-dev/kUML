package dev.kuml.sysml2.constraint

import dev.kuml.expr.ExpressionTypeChecker
import dev.kuml.expr.KumlType
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.sysml2.AttributeDefinition
import dev.kuml.sysml2.AttributeUsage
import dev.kuml.sysml2.BindingConnectorUsage
import dev.kuml.sysml2.ConstraintDefinition
import dev.kuml.sysml2.ParDiagram
import dev.kuml.sysml2.Sysml2Model

/**
 * Type-checks PAR [ConstraintDefinition.expression] strings against the
 * types of the attributes bound to its parameters via [BindingConnectorUsage]s.
 *
 * V2.0.20b MVP:
 * - Resolves each [ConstraintDefinition.parameters] name to a [KumlType] by
 *   following the corresponding [BindingConnectorUsage] to the connected
 *   [AttributeDefinition] name, then applying a name-heuristic to infer the
 *   numeric kind (Real vs Int vs Unknown).
 * - Parses the expression string and type-checks it against the resolved env.
 * - Reports [ConstraintTypeError] for mismatches; returns empty list on success.
 *
 * V2.x deferred: constraint solving / equation system, recursive type inference
 * for nested constraints.
 *
 * **Consolidation note (V3.2.23)**: this checker is deliberately kept separate
 * from `kuml-core-ocl`'s `OclValidator` / `OclParser`. It type-checks PAR
 * (parametric) equations bound to attributes via [BindingConnectorUsage]s using
 * [OclLikeExpressionParser] (`kuml-core-expr`) — a lightweight expression parser
 * shared with STM/ACT guard conditions. `OclValidator` instead evaluates full
 * OCL invariants (e.g. `dev.kuml.sysml2.PartDefinition.constraints`) with `self`
 * bound to a model element. The two engines coexist rather than merge: PAR
 * constraint type-checking has no `self`/navigation context and does not need
 * the full OCL grammar, while classifier invariants need exactly that. If PAR
 * constraints ever require OCL-level expressiveness (navigation, collection
 * operations), revisit routing [OclLikeExpressionParser] through the full
 * `kuml-core-ocl` interpreter instead of maintaining two parsers.
 */
public object Sysml2ConstraintChecker {
    /**
     * Error reported when a constraint expression fails type-checking or cannot
     * be parsed.
     *
     * @property constraintId  id of the [ConstraintDefinition] that failed.
     * @property expression    the raw expression string that was checked.
     * @property message       human-readable error message.
     * @property column        1-based column of the error, or -1 if unknown.
     */
    public data class ConstraintTypeError(
        val constraintId: String,
        val expression: String,
        val message: String,
        val column: Int = -1,
    )

    /**
     * Checks all [ConstraintDefinition]s in [model] that appear in [diagram]
     * (or in the full model if [diagram] is null).
     *
     * @param model   the SysML 2 model to check.
     * @param diagram if non-null, restricts checking to constraints whose ids
     *   appear in [ParDiagram.elementIds].
     * @return list of errors; empty list if all constraints type-check.
     */
    public fun check(
        model: Sysml2Model,
        diagram: ParDiagram? = null,
    ): List<ConstraintTypeError> {
        val visibleIds = diagram?.elementIds?.toSet()

        val constraints =
            model.definitions
                .filterIsInstance<ConstraintDefinition>()
                .let { all -> if (visibleIds != null) all.filter { it.id in visibleIds } else all }

        val errors = mutableListOf<ConstraintTypeError>()

        for (constraint in constraints) {
            // Skip constraints with no expression (nothing to check)
            if (constraint.expression.isBlank()) continue

            // Build the type environment for this constraint's parameters
            val typeEnv = buildTypeEnv(constraint.id, constraint.parameters.map { it.name }, model)

            // Parse the expression. PAR constraints commonly use mathematical `=`
            // for equality (e.g. `F = m * a`), which the OCL-like parser does not
            // support as a token.  We normalise bare `=` → `==` before parsing so
            // that mathematical equality expressions type-check correctly.
            // A bare `=` is defined as one not preceded or followed by `=`, `!`,
            // `<`, or `>`.
            val normalised = normaliseEquality(constraint.expression)
            val parseErrors = mutableListOf<dev.kuml.expr.ParseError>()
            val parsed = OclLikeExpressionParser.tryParse(normalised, parseErrors)
            if (parsed == null) {
                val msg = parseErrors.firstOrNull()?.message ?: "failed to parse"
                val col = parseErrors.firstOrNull()?.column ?: -1
                errors +=
                    ConstraintTypeError(
                        constraintId = constraint.id,
                        expression = constraint.expression, // report original (not normalised) expression
                        message = "failed to parse: $msg",
                        column = col,
                    )
                continue
            }

            // Type-check the parsed expression
            val inferredType = ExpressionTypeChecker.infer(parsed, typeEnv)
            if (inferredType is KumlType.TypeError) {
                errors +=
                    ConstraintTypeError(
                        constraintId = constraint.id,
                        expression = constraint.expression,
                        message = inferredType.message,
                        column = inferredType.column,
                    )
            }
        }

        return errors
    }

    /**
     * Normalises bare `=` (mathematical equality) to `==` (OCL equality operator)
     * so that PAR constraint expressions like `F = m * a` can be parsed by the
     * OCL-like expression parser.
     *
     * A bare `=` is one not preceded by `=`, `!`, `<`, `>` and not followed by `=`.
     * String literal contents are left untouched.
     */
    private fun normaliseEquality(src: String): String {
        val sb = StringBuilder(src.length + 4)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            // Skip string literals unchanged
            if (c == '\'' || c == '"') {
                val q = c
                sb.append(c)
                i++
                while (i < src.length && src[i] != q) {
                    if (src[i] == '\\' && i + 1 < src.length) {
                        sb.append(src[i])
                        sb.append(src[i + 1])
                        i += 2
                    } else {
                        sb.append(src[i++])
                    }
                }
                if (i < src.length) {
                    sb.append(src[i++]) // closing quote
                }
                continue
            }
            if (c == '=') {
                val prev = if (i > 0) src[i - 1] else ' '
                val next = if (i + 1 < src.length) src[i + 1] else ' '
                val isBare = next != '=' && prev != '!' && prev != '<' && prev != '>' && prev != '='
                if (isBare) {
                    sb.append("==")
                    i++
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * Builds a type environment mapping parameter names to [KumlType]s.
     *
     * Resolution logic (V2.0.20b heuristic):
     *  1. For each parameter name, scan [BindingConnectorUsage]s whose
     *     [BindingConnectorUsage.sourceEndId] starts with
     *     `"<constraintId>::<paramName>"` — this is the canonical format the
     *     DSL and kUML tooling use for constraint parameter pin ids.
     *  2. From the matching binding, extract the [AttributeDefinition] on the
     *     other end (longest-prefix-match against model definitions).
     *  3. Apply a name-based heuristic to the `AttributeDefinition.name`:
     *     - Names associated with physical real quantities → [KumlType.Real]
     *     - Names associated with integer counts or indices → [KumlType.Int]
     *     - Otherwise → [KumlType.Unknown] (not an error in V2.0.20b)
     */
    private fun buildTypeEnv(
        constraintId: String,
        paramNames: List<String>,
        model: Sysml2Model,
    ): Map<String, KumlType> {
        val bindings = model.usages.filterIsInstance<BindingConnectorUsage>()
        val attrDefs = model.definitions.filterIsInstance<AttributeDefinition>()
        // All attribute usages in the model (top-level usages + could be nested but V2.0.20b only looks top-level)
        val attrUsages = model.usages.filterIsInstance<AttributeUsage>()

        return paramNames.associateWith { paramName ->
            val pinId = "$constraintId::$paramName"
            // Find binding whose source or target matches the pin id
            val binding =
                bindings.firstOrNull { b ->
                    b.sourceEndId == pinId ||
                        b.sourceEndId.startsWith("$pinId:") ||
                        b.targetEndId == pinId ||
                        b.targetEndId.startsWith("$pinId:")
                }
            if (binding == null) {
                KumlType.Unknown
            } else {
                // The "other end" of the binding — the attribute reference side
                val otherEnd =
                    if (binding.sourceEndId == pinId ||
                        binding.sourceEndId.startsWith("$pinId:")
                    ) {
                        binding.targetEndId
                    } else {
                        binding.sourceEndId
                    }
                resolveAttributeType(otherEnd, attrDefs, attrUsages)
            }
        }
    }

    /**
     * Resolves the [KumlType] of an attribute endpoint reference like `"P::force"`.
     *
     * Resolution steps (in order):
     * 1. Extract the attribute-usage name from the endpoint (after last `"::"` if present).
     * 2. Find a matching [AttributeUsage] in the model by name (case-insensitive).
     *    Follow its [AttributeUsage.definitionId] to an [AttributeDefinition].
     * 3. Direct case-insensitive match of [AttributeDefinition] name/id against the endpoint.
     * 4. Name-heuristic on the local name as last resort.
     */
    private fun resolveAttributeType(
        endpointId: String,
        attrDefs: List<AttributeDefinition>,
        attrUsages: List<AttributeUsage>,
    ): KumlType {
        // Extract the local name — the part after the last "::" if present.
        val localName = if (endpointId.contains("::")) endpointId.substringAfterLast("::") else endpointId

        // Step 1: try to find an AttributeUsage with this name (case-insensitive)
        val attrUsage = attrUsages.firstOrNull { it.name.equals(localName, ignoreCase = true) }
        if (attrUsage != null) {
            // Follow the usage's definitionId to an AttributeDefinition
            val attrDef =
                attrDefs.firstOrNull { it.id.equals(attrUsage.definitionId, ignoreCase = true) }
            if (attrDef != null) return inferTypeFromName(attrDef.name)
        }

        // Step 2: direct name/id match on AttributeDefinition (case-insensitive)
        val directDef =
            attrDefs.firstOrNull { d ->
                d.name.equals(localName, ignoreCase = true) ||
                    d.id.equals(localName, ignoreCase = true) ||
                    endpointId.contains(d.name, ignoreCase = true)
            }
        if (directDef != null) return inferTypeFromName(directDef.name)

        // Step 3: apply heuristic on the local name itself (last resort)
        return inferTypeFromName(localName)
    }

    /**
     * Infers a [KumlType] from an [AttributeDefinition] name using a simple
     * name-based heuristic.
     *
     * This is intentionally conservative: physical quantities that are
     * mathematically real-valued are mapped to [KumlType.Real]; discrete
     * counters / indices to [KumlType.Int]; anything else to [KumlType.Unknown].
     *
     * A proper typed-attribute system (V2.x) will replace this heuristic with
     * an explicit type annotation on [AttributeDefinition].
     */
    private fun inferTypeFromName(name: String): KumlType {
        val lower = name.lowercase()
        return when {
            // Integer-like concepts
            intKeywords.any { lower.contains(it) } -> KumlType.Int

            // Real-valued physical quantities and other continuous values
            realKeywords.any { lower.contains(it) } -> KumlType.Real

            // Boolean flags
            boolKeywords.any { lower.contains(it) } -> KumlType.Bool

            else -> KumlType.Unknown
        }
    }

    // ── Keyword sets for type heuristic ──────────────────────────────────────

    private val intKeywords: Set<String> =
        setOf("count", "index", "number", "integer", "quantity")

    private val realKeywords: Set<String> =
        setOf(
            "mass",
            "force",
            "acceleration",
            "velocity",
            "speed",
            "pressure",
            "temperature",
            "energy",
            "power",
            "torque",
            "voltage",
            "current",
            "resistance",
            "frequency",
            "length",
            "distance",
            "angle",
            "time",
            "weight",
            "density",
            "flow",
            "rate",
            "ratio",
            "real",
            "float",
            "double",
        )

    private val boolKeywords: Set<String> =
        setOf("flag", "bool", "enabled", "active")
}
