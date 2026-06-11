package dev.kuml.runtime.sandbox

import dev.kuml.expr.AssignEffect
import dev.kuml.expr.AttributeRef
import dev.kuml.expr.BinaryOp
import dev.kuml.expr.CallEffect
import dev.kuml.expr.ExpressionEffect
import dev.kuml.expr.ExpressionEvaluator
import dev.kuml.expr.FunctionCall
import dev.kuml.expr.KumlEffect
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.LiteralBool
import dev.kuml.expr.LiteralInt
import dev.kuml.expr.LiteralNull
import dev.kuml.expr.LiteralReal
import dev.kuml.expr.LiteralString
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.expr.UnaryOp
import dev.kuml.runtime.Event
import dev.kuml.runtime.StateMachineInstance
import dev.kuml.runtime.toFlatEvalMap

/**
 * Executes KumlEffect sequences against a [StateMachineInstance] within
 * the constraints of a [SandboxPolicy].
 *
 * V2.0.40 — Sandbox-Garantien.
 */
public class EffectExecutor(
    private val policy: SandboxPolicy,
) {
    private val reservedNames = setOf("self", "event", "vars", "__log__")

    /**
     * Parses and executes [actionBody] against [instance] in the context of [event].
     *
     * @throws SandboxException on any policy violation.
     */
    public fun execute(
        actionBody: String,
        instance: StateMachineInstance,
        event: Event,
    ) {
        if (actionBody.isBlank()) return

        val effects =
            try {
                OclLikeExpressionParser.tryParseEffects(actionBody)
                    ?: throw SandboxException.ParseFailure(
                        RuntimeException("Could not parse action body: $actionBody"),
                    )
            } catch (ex: SandboxException) {
                throw ex
            } catch (ex: Throwable) {
                throw SandboxException.ParseFailure(ex)
            }

        if (effects.size > policy.maxEffectsPerAction) {
            throw SandboxException.TooManyEffects(policy.maxEffectsPerAction)
        }

        for (effect in effects) {
            if (depthEffect(effect) > policy.maxExpressionDepth) {
                throw SandboxException.ExpressionTooDeep(policy.maxExpressionDepth)
            }
            executeOne(effect, instance, event)
            enforceLimits(instance)
        }
    }

    private fun executeOne(
        effect: KumlEffect,
        instance: StateMachineInstance,
        event: Event,
    ) {
        when (effect) {
            is CallEffect -> executeCall(effect, instance, event)
            is AssignEffect -> executeAssign(effect, instance, event)
            is ExpressionEffect -> evaluateExpressionEffect(effect.expr, instance, event)
        }
    }

    private fun executeCall(
        effect: CallEffect,
        instance: StateMachineInstance,
        event: Event,
    ) {
        val key = effect.receiver.joinToString(".")
        val context = buildContext(instance, event)
        val evaluatedArgs = effect.args.map { evaluateExpr(it, context) }

        // Check allowlist
        if (key !in policy.allowedFunctions) {
            throw SandboxException.DisallowedFunction(key)
        }

        val impl =
            BuiltInFunctions.lookup(key)
                ?: throw SandboxException.DisallowedFunction(key)

        val result = impl.call(evaluatedArgs)

        // For log.* functions, append to __log__ list
        if (effect.receiver.firstOrNull() == "log" && result is String) {
            @Suppress("UNCHECKED_CAST")
            val logList =
                instance.variables.getOrPut("__log__") { mutableListOf<String>() } as? MutableList<String>
                    ?: run {
                        val newList = mutableListOf<String>()
                        instance.variables["__log__"] = newList
                        newList
                    }
            logList.add("${effect.receiver.lastOrNull() ?: "log"}: $result")
        }
    }

    private fun executeAssign(
        effect: AssignEffect,
        instance: StateMachineInstance,
        event: Event,
    ) {
        val context = buildContext(instance, event)
        val value = evaluateExpr(effect.value, context)

        when (effect.target.size) {
            0 -> return // nothing to assign
            1 -> {
                val key = effect.target[0]
                if (key in reservedNames) throw SandboxException.ReservedVariableName(key)
                if (value is String && value.length > policy.maxStringLength) {
                    throw SandboxException.StringLengthExceeded(key, policy.maxStringLength)
                }
                instance.variables[key] = value
            }
            else -> {
                // Nested path: build MutableMaps as needed
                val rootKey = effect.target[0]
                if (rootKey in reservedNames) throw SandboxException.ReservedVariableName(rootKey)
                val finalKey = effect.target.last()
                if (value is String && value.length > policy.maxStringLength) {
                    throw SandboxException.StringLengthExceeded(effect.target.joinToString("."), policy.maxStringLength)
                }
                // Navigate / create intermediate maps
                var current: MutableMap<String, Any?> =
                    when (val existing = instance.variables[rootKey]) {
                        is MutableMap<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            existing as MutableMap<String, Any?>
                        }
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            (existing as Map<String, Any?>).toMutableMap().also { instance.variables[rootKey] = it }
                        }
                        else -> mutableMapOf<String, Any?>().also { instance.variables[rootKey] = it }
                    }
                for (segment in effect.target.drop(1).dropLast(1)) {
                    val sub = current[segment]
                    current =
                        when (sub) {
                            is MutableMap<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                sub as MutableMap<String, Any?>
                            }
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                (sub as Map<String, Any?>).toMutableMap().also { current[segment] = it }
                            }
                            else -> mutableMapOf<String, Any?>().also { current[segment] = it }
                        }
                }
                current[finalKey] = value
            }
        }
    }

    private fun evaluateExpressionEffect(
        expr: KumlExpression,
        instance: StateMachineInstance,
        event: Event,
    ) {
        val context = buildContext(instance, event)
        // For function calls in expression position, also enforce the whitelist
        if (expr is FunctionCall && expr.name !in policy.allowedFunctions) {
            throw SandboxException.DisallowedFunction(expr.name)
        }
        evaluateExpr(expr, context)
        // Result is discarded — ExpressionEffect is a bare expression statement
    }

    private fun evaluateExpr(
        expr: KumlExpression,
        context: Map<String, Any?>,
    ): Any? {
        // Enforce function whitelist at FunctionCall nodes
        checkFunctionWhitelist(expr)
        return ExpressionEvaluator.evaluate(expr, context)
    }

    private fun checkFunctionWhitelist(expr: KumlExpression) {
        when (expr) {
            is FunctionCall -> {
                if (expr.name !in policy.allowedFunctions) {
                    throw SandboxException.DisallowedFunction(expr.name)
                }
                expr.args.forEach { checkFunctionWhitelist(it) }
            }
            is BinaryOp -> {
                checkFunctionWhitelist(expr.left)
                checkFunctionWhitelist(expr.right)
            }
            is UnaryOp -> checkFunctionWhitelist(expr.operand)
            else -> Unit
        }
    }

    private fun buildContext(
        instance: StateMachineInstance,
        event: Event,
    ): Map<String, Any?> =
        buildMap {
            putAll(instance.variables)
            put("event", event.toFlatEvalMap())
            put("vars", instance.variables)
        }

    private fun enforceLimits(instance: StateMachineInstance) {
        if (instance.variables.size > policy.maxVariableCount) {
            throw SandboxException.VariableLimitExceeded(policy.maxVariableCount)
        }
        for ((key, value) in instance.variables) {
            if (value is String && value.length > policy.maxStringLength) {
                throw SandboxException.StringLengthExceeded(key, policy.maxStringLength)
            }
        }
    }
}

// ── AST depth helpers ────────────────────────────────────────────────────────

internal fun depthExpr(expr: KumlExpression): Int =
    when (expr) {
        is LiteralBool, is LiteralInt, is LiteralReal, is LiteralString, LiteralNull -> 0
        is AttributeRef -> 0
        is FunctionCall -> 1 + (expr.args.maxOfOrNull { depthExpr(it) } ?: 0)
        is BinaryOp -> 1 + maxOf(depthExpr(expr.left), depthExpr(expr.right))
        is UnaryOp -> 1 + depthExpr(expr.operand)
    }

internal fun depthEffect(effect: KumlEffect): Int =
    when (effect) {
        is CallEffect -> 1 + (effect.args.maxOfOrNull { depthExpr(it) } ?: 0)
        is AssignEffect -> 1 + depthExpr(effect.value)
        is ExpressionEffect -> depthExpr(effect.expr)
    }
