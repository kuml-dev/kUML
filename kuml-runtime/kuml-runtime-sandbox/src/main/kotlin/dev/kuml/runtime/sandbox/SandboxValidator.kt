package dev.kuml.runtime.sandbox

import dev.kuml.expr.AssignEffect
import dev.kuml.expr.AttributeRef
import dev.kuml.expr.BinaryOp
import dev.kuml.expr.CallEffect
import dev.kuml.expr.ExpressionEffect
import dev.kuml.expr.FunctionCall
import dev.kuml.expr.KumlEffect
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.expr.UnaryOp
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlVertex

/**
 * Kind of sandbox policy violation found during static analysis.
 */
public enum class ViolationKind {
    /** A function call references a name not in the policy's allowed list. */
    DISALLOWED_FUNCTION,

    /** An expression or effect AST exceeds the allowed depth. */
    EXPRESSION_TOO_DEEP,

    /** An assignment targets a reserved variable name. */
    RESERVED_VARIABLE_NAME,

    /** The action body or guard could not be parsed. */
    PARSE_ERROR,

    /** A single action body contains more effects than the policy allows. */
    TOO_MANY_EFFECTS,
}

/** Location of a violation in the model. */
public data class ViolationLocation(
    public val vertexId: String? = null,
    public val transitionId: String? = null,
    public val phase: String? = null,
)

/**
 * A single sandbox policy violation found by [SandboxValidator].
 */
public data class SandboxViolation(
    public val kind: ViolationKind,
    public val location: ViolationLocation,
    public val rawText: String,
    public val message: String,
)

/**
 * Result of [SandboxValidator.validate].
 */
public data class SandboxValidationReport(
    public val violations: List<SandboxViolation>,
) {
    public val isClean: Boolean get() = violations.isEmpty()
}

/**
 * Static analysis of a [UmlStateMachine] for sandbox policy compliance.
 *
 * Inspects all guard and action body strings without executing them.
 * Use this to validate a model before enabling sandbox execution.
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public class SandboxValidator(
    public val policy: SandboxPolicy,
) {
    private val reservedNames = setOf("self", "event", "vars", "__log__")

    /** Validates [model] and returns a report with all found violations. */
    public fun validate(model: UmlStateMachine): SandboxValidationReport {
        val violations = mutableListOf<SandboxViolation>()

        for (vertex in allVertices(model)) {
            if (vertex is UmlState) {
                vertex.entry?.let { body ->
                    violations += checkAction(body, ViolationLocation(vertexId = vertex.id, phase = "entry"))
                }
                vertex.exit?.let { body ->
                    violations += checkAction(body, ViolationLocation(vertexId = vertex.id, phase = "exit"))
                }
                vertex.doActivity?.let { body ->
                    violations += checkAction(body, ViolationLocation(vertexId = vertex.id, phase = "doActivity"))
                }
            }
        }

        for (transition in model.transitions) {
            transition.guard?.let { guard ->
                violations +=
                    checkGuard(
                        guard,
                        ViolationLocation(transitionId = transition.id, phase = "guard"),
                    )
            }
            transition.effect?.let { body ->
                violations +=
                    checkAction(
                        body,
                        ViolationLocation(transitionId = transition.id, phase = "effect"),
                    )
            }
        }

        return SandboxValidationReport(violations)
    }

    private fun checkAction(
        body: String,
        location: ViolationLocation,
    ): List<SandboxViolation> {
        if (body.isBlank()) return emptyList()
        val errors = mutableListOf<dev.kuml.expr.ParseError>()
        val effects = OclLikeExpressionParser.tryParseEffects(body, errors)
        if (effects == null) {
            return listOf(
                SandboxViolation(
                    kind = ViolationKind.PARSE_ERROR,
                    location = location,
                    rawText = body,
                    message = "Parse error: ${errors.firstOrNull()?.message ?: "unknown"}",
                ),
            )
        }
        val violations = mutableListOf<SandboxViolation>()
        if (effects.size > policy.maxEffectsPerAction) {
            violations +=
                SandboxViolation(
                    kind = ViolationKind.TOO_MANY_EFFECTS,
                    location = location,
                    rawText = body,
                    message = "Effect count ${effects.size} exceeds limit ${policy.maxEffectsPerAction}",
                )
        }
        for (effect in effects) {
            if (depthEffect(effect) > policy.maxExpressionDepth) {
                violations +=
                    SandboxViolation(
                        kind = ViolationKind.EXPRESSION_TOO_DEEP,
                        location = location,
                        rawText = body,
                        message = "Expression depth ${depthEffect(effect)} exceeds limit ${policy.maxExpressionDepth}",
                    )
            }
            // Check function names in this effect
            for (fnName in collectFunctionNamesEffect(effect)) {
                if (fnName !in policy.allowedFunctions) {
                    violations +=
                        SandboxViolation(
                            kind = ViolationKind.DISALLOWED_FUNCTION,
                            location = location,
                            rawText = body,
                            message = "Function '$fnName' is not in the sandbox allowlist",
                        )
                }
            }
            // Check for reserved assignments
            if (effect is AssignEffect && effect.target.isNotEmpty()) {
                val rootKey = effect.target[0]
                if (rootKey in reservedNames) {
                    violations +=
                        SandboxViolation(
                            kind = ViolationKind.RESERVED_VARIABLE_NAME,
                            location = location,
                            rawText = body,
                            message = "Cannot assign to reserved variable name '$rootKey'",
                        )
                }
            }
        }
        return violations
    }

    private fun checkGuard(
        guard: String,
        location: ViolationLocation,
    ): List<SandboxViolation> {
        if (guard.isBlank()) return emptyList()
        val cleaned =
            guard.trim().let {
                if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1).trim() else it
            }
        val errors = mutableListOf<dev.kuml.expr.ParseError>()
        val expr = OclLikeExpressionParser.tryParse(cleaned, errors)
        if (expr == null) {
            return listOf(
                SandboxViolation(
                    kind = ViolationKind.PARSE_ERROR,
                    location = location,
                    rawText = guard,
                    message = "Guard parse error: ${errors.firstOrNull()?.message ?: "unknown"}",
                ),
            )
        }
        val violations = mutableListOf<SandboxViolation>()
        if (depthExpr(expr) > policy.maxExpressionDepth) {
            violations +=
                SandboxViolation(
                    kind = ViolationKind.EXPRESSION_TOO_DEEP,
                    location = location,
                    rawText = guard,
                    message = "Guard depth ${depthExpr(expr)} exceeds limit ${policy.maxExpressionDepth}",
                )
        }
        for (fnName in collectFunctionNamesExpr(expr)) {
            if (fnName !in policy.allowedFunctions) {
                violations +=
                    SandboxViolation(
                        kind = ViolationKind.DISALLOWED_FUNCTION,
                        location = location,
                        rawText = guard,
                        message = "Function '$fnName' is not in the sandbox allowlist",
                    )
            }
        }
        return violations
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun allVertices(model: UmlStateMachine): List<UmlVertex> {
    val out = mutableListOf<UmlVertex>()

    fun visit(v: UmlVertex) {
        out += v
        if (v is UmlState) v.substates.forEach { visit(it) }
    }
    model.vertices.forEach { visit(it) }
    return out
}

internal fun collectFunctionNamesEffect(effect: KumlEffect): List<String> =
    when (effect) {
        is CallEffect -> {
            val name = effect.receiver.joinToString(".")
            buildList {
                add(name)
                effect.args.flatMapTo(this) { collectFunctionNamesExpr(it) }
            }
        }
        is AssignEffect -> collectFunctionNamesExpr(effect.value)
        is ExpressionEffect -> collectFunctionNamesExpr(effect.expr)
    }

internal fun collectFunctionNamesExpr(expr: KumlExpression): List<String> =
    when (expr) {
        is FunctionCall ->
            buildList {
                add(expr.name)
                expr.args.flatMapTo(this) { collectFunctionNamesExpr(it) }
            }
        is BinaryOp -> collectFunctionNamesExpr(expr.left) + collectFunctionNamesExpr(expr.right)
        is UnaryOp -> collectFunctionNamesExpr(expr.operand)
        is AttributeRef -> emptyList()
        else -> emptyList()
    }
