package dev.kuml.runtime

import dev.kuml.core.ocl.OclEvaluationException
import dev.kuml.core.ocl.OclExpressions
import dev.kuml.expr.EvaluationException
import dev.kuml.expr.ExpressionEvaluator
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.runtime.internal.toEvalMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Default-Implementierung von [GuardEvaluator], die das OCL-Subset aus
 * `kuml-core-ocl` zum Auswerten von Transitions-Guards verwendet.
 *
 * V2.0.20a: adds a lazy-parse cache backed by [OclLikeExpressionParser]. If the
 * typed-AST path can parse and evaluate the guard expression, it takes
 * precedence.  On any parse or evaluation failure the legacy
 * [OclExpressions.evaluate] path ([evaluateLegacy]) is used transparently —
 * full backward-compatibility is preserved.
 *
 * Konventionen:
 *  - `null` oder leerer Guard → [GuardResult.True] (UML 2.5 §15.3.13).
 *  - Eckige Klammern an Anfang/Ende des Guard-Strings werden vor dem
 *    Parse entfernt: `"[isValid]"` → `"isValid"`.
 *  - `self` im OCL-Kontext ist die [ModelInstance].
 *  - `env["event"]` ist eine flache Map-View über das aktuelle [Event]
 *    (Name + Payload-Felder flach).
 *  - `env["vars"]` ist die `variables`-Map der Instanz.
 */
public class OclGuardEvaluator : GuardEvaluator {
    // V2.0.20a — thread-safe lazy-parse cache.
    // ConcurrentHashMap does not allow null values, so we use two maps:
    //  - parsedCache: guard → successfully parsed KumlExpression
    //  - unparseable: set of guards the new AST parser could not handle
    // Together they act as a ConcurrentHashMap<String, KumlExpression?> with null-support.
    private val parsedCache: ConcurrentHashMap<String, KumlExpression> = ConcurrentHashMap()
    private val unparseable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult {
        if (guard.isNullOrBlank()) return GuardResult.True
        val cleaned = stripBrackets(guard)

        // V2.0.20a: build the flat eval context that both paths share.
        val context: Map<String, Any?> =
            mapOf(
                "event" to event.toEvalMap(),
                "vars" to instance.variables,
            )

        // Retrieve or compute the cached parsed expression.
        // ConcurrentHashMap.getOrPut is not atomic, but the worst case is
        // that we parse the same guard twice on first-access concurrency —
        // that is harmless.
        val cached: KumlExpression? =
            when {
                parsedCache.containsKey(cleaned) -> parsedCache[cleaned]
                unparseable.contains(cleaned) -> null
                else -> {
                    val parsed = OclLikeExpressionParser.tryParse(cleaned)
                    if (parsed != null) {
                        parsedCache[cleaned] = parsed
                    } else {
                        unparseable.add(cleaned)
                    }
                    parsed
                }
            }

        return if (cached != null) {
            // AST evaluator path: only trust it when it returns a definite Boolean.
            // Null or non-boolean results fall back to the legacy OCL path so that
            // navigation errors and unknown-path guards behave identically to V2.0.19.
            try {
                val raw = ExpressionEvaluator.evaluate(cached, flattenContext(context))
                when (raw) {
                    true -> GuardResult.True
                    false -> GuardResult.False
                    else -> {
                        // null or non-boolean — fall back to legacy for correct error semantics.
                        evaluateLegacy(cleaned, instance, event)
                    }
                }
            } catch (_: EvaluationException) {
                // AST evaluation failed — fall back to legacy.
                evaluateLegacy(cleaned, instance, event)
            }
        } else {
            // New parser could not handle this guard (in unparseable set); use legacy path.
            evaluateLegacy(cleaned, instance, event)
        }
    }

    /**
     * Legacy OCL evaluation via [OclExpressions.evaluate]. Called when the
     * typed-AST parser cannot handle the guard, or when AST evaluation fails.
     *
     * This is the V1.1.x–V2.0.19 implementation, preserved verbatim.
     */
    private fun evaluateLegacy(
        cleaned: String,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult =
        try {
            val env: Map<String, Any?> =
                mapOf(
                    "event" to event.toEvalMap(),
                    "vars" to instance.variables,
                )
            val raw = OclExpressions.evaluate(cleaned, self = instance, env = env)
            when (raw) {
                true -> GuardResult.True
                false -> GuardResult.False
                null -> GuardResult.False
                else -> GuardResult.Failed("Guard expression did not evaluate to Boolean (got $raw)")
            }
        } catch (ex: OclEvaluationException) {
            GuardResult.Failed(ex.message ?: ex.javaClass.simpleName)
        } catch (ex: IllegalArgumentException) {
            GuardResult.Failed("Guard parse error: ${ex.message ?: ex.javaClass.simpleName}")
        } catch (ex: IllegalStateException) {
            GuardResult.Failed("Guard error: ${ex.message ?: ex.javaClass.simpleName}")
        }

    /**
     * Flatten the nested context map (with "event" and "vars" sub-maps) into a
     * single flat map so that guard expressions like `event.temperature` can be
     * accessed as `context["event"]` → Map → ["temperature"].
     *
     * The evaluator resolves single-segment refs directly from the top-level
     * context map; multi-segment refs (`event.temperature`) are resolved by
     * navigating into nested maps via the evaluator's AttributeRef logic.
     */
    private fun flattenContext(context: Map<String, Any?>): Map<String, Any?> = context

    private fun stripBrackets(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("[") && t.endsWith("]")) {
            t.substring(1, t.length - 1).trim()
        } else {
            t
        }
    }
}
