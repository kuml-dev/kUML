package dev.kuml.ai.tools.patch.validation

// Type-check phase for guard / effect patches (V3.0.25).
// Uses the V2.0.20a OclLikeExpressionParser best-effort: expressions that cannot
// be typed yield a WARNING rather than an error, because the type system is not
// yet complete for all custom-stereotype attribute types.

import dev.kuml.ai.tools.context.ModelPatch
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.runtime.sandbox.SandboxPolicy

/** Fields on UpdateAttribute that carry expression strings and need type-checking. */
private val EXPRESSION_FIELDS = setOf("guard", "effect", "entry", "exit", "doActivity")

/**
 * Runs best-effort type checks on expression-carrying patches.
 *
 * @return Pair of (errors, warnings). Errors stop the pipeline; warnings are
 *   advisory (the patch is still valid).
 */
internal object TypeCheckPatchChecks {
    internal fun run(
        patch: ModelPatch,
        policy: SandboxPolicy,
        warnings: MutableList<String>,
    ): List<ValidationError> {
        if (patch !is ModelPatch.UpdateAttribute) return emptyList()
        if (patch.field !in EXPRESSION_FIELDS) return emptyList()

        val expr = patch.newValue
        if (expr.isBlank()) return emptyList()

        val parseErrors = mutableListOf<dev.kuml.expr.ParseError>()

        return when (patch.field) {
            "guard" -> {
                val cleaned =
                    expr.trim().let {
                        if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1).trim() else it
                    }
                val parsed = OclLikeExpressionParser.tryParse(cleaned, parseErrors)
                if (parsed == null) {
                    // Parse failure is a type-check warning (not error) — best effort
                    warnings.add("Guard expression could not be parsed: ${parseErrors.firstOrNull()?.message ?: "unknown"}")
                    emptyList()
                } else {
                    // Check function names against allowlist
                    checkFunctionNames(collectFunctionNames(parsed), policy, warnings)
                }
            }
            "effect", "entry", "exit", "doActivity" -> {
                val effects = OclLikeExpressionParser.tryParseEffects(expr, parseErrors)
                if (effects == null) {
                    warnings.add("Effect expression could not be parsed: ${parseErrors.firstOrNull()?.message ?: "unknown"}")
                    emptyList()
                } else {
                    val errors = mutableListOf<ValidationError>()
                    for (effect in effects) {
                        errors.addAll(
                            checkFunctionNames(collectFunctionNamesFromEffect(effect), policy, warnings),
                        )
                    }
                    errors
                }
            }
            else -> emptyList()
        }
    }

    private fun checkFunctionNames(
        names: List<String>,
        policy: SandboxPolicy,
        warnings: MutableList<String>,
    ): List<ValidationError> {
        // Skip type-check function-name validation only when no function names are present
        if (names.isEmpty()) return emptyList()
        val errors = mutableListOf<ValidationError>()
        for (name in names) {
            if (name !in policy.allowedFunctions) {
                errors.add(
                    ValidationError(
                        code = "DISALLOWED_FUNCTION",
                        message = "Function '$name' is not in the sandbox allowlist.",
                        locationHint = name,
                    ),
                )
            }
        }
        return errors
    }

    private fun collectFunctionNames(expr: dev.kuml.expr.KumlExpression): List<String> =
        when (expr) {
            is dev.kuml.expr.FunctionCall ->
                buildList {
                    add(expr.name)
                    expr.args.flatMapTo(this) { collectFunctionNames(it) }
                }
            is dev.kuml.expr.BinaryOp -> collectFunctionNames(expr.left) + collectFunctionNames(expr.right)
            is dev.kuml.expr.UnaryOp -> collectFunctionNames(expr.operand)
            else -> emptyList()
        }

    private fun collectFunctionNamesFromEffect(effect: dev.kuml.expr.KumlEffect): List<String> =
        when (effect) {
            is dev.kuml.expr.CallEffect -> {
                val name = effect.receiver.joinToString(".")
                buildList {
                    add(name)
                    effect.args.flatMapTo(this) { collectFunctionNames(it) }
                }
            }
            is dev.kuml.expr.AssignEffect -> collectFunctionNames(effect.value)
            is dev.kuml.expr.ExpressionEffect -> collectFunctionNames(effect.expr)
        }
}
