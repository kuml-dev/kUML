package dev.kuml.runtime

import dev.kuml.core.ocl.OclEvaluationException
import dev.kuml.core.ocl.OclExpressions
import dev.kuml.expr.EvaluationException
import dev.kuml.expr.ExpressionEvaluator
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.runtime.internal.GuardAstTaint
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
 *
 * ## Fail-closed comparisons against a missing variable
 *
 * Both evaluation paths are null-tolerant: [ExpressionEvaluator]'s `==`/`!=`
 * and the legacy `dev.kuml.core.ocl` front-end's `<>` resolve a missing
 * `vars.x` / `event.x` / bare-identifier lookup to `null` exactly like a value
 * that is present and genuinely `null`, so `vars.x <> 1` (or `vars.x != 1`)
 * with a *missing* `x` would otherwise evaluate to a trusted `true` instead of
 * signalling "unknown" — a guard firing on data that was never provided. This
 * evaluator closes that gap on **both** dialects and **both** internal paths:
 *  - The AST path ([evaluateViaAst]) only trusts a `true`
 *    [ExpressionEvaluator] result once [GuardAstTaint.referencesUnresolvedVariable]
 *    confirms every variable the expression *actually visited* (respecting
 *    `&&`/`||` short-circuiting) was present; otherwise it defers to
 *    [evaluateLegacy] the same way a `null`/non-Boolean AST result already
 *    did.
 *  - The legacy OCL path ([evaluateLegacy]) uses [OclExpressions.evaluateTracked]
 *    instead of the plain `evaluate`, so a `true` result built on a missing
 *    `env` variable is downgraded to [GuardResult.Failed] — the terminal
 *    answer, since there is no further dialect to fall back to here.
 *
 * A `false` result is trusted unchanged on both paths: it is already the safe
 * direction (the transition does not fire) whether a referenced variable was
 * missing or present-and-genuinely-`false`. `vars.x.oclIsUndefined()` remains
 * the documented way to test for absence and is unaffected — it is a
 * receiver-navigation query, not a comparison against a lookup, and the
 * `dev.kuml.core.ocl` front-end already treats a plain lookup-chain receiver
 * of `oclIsUndefined()` as an intentional presence check (see `OclEvaluator`).
 *
 * See [dev.kuml.runtime.activity.ActivityGuardEvaluator] for the identical
 * hardening on the Activity/BPMN token-flow path, and [GuardAstTaint] for the
 * shared AST-side implementation both evaluators delegate to.
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

        // V2.0.20a: build the flat eval context that both paths share. Top-level
        // keys are "event" and "vars" only — guard expressions navigate into them
        // via dot-paths (event.foo, vars.foo), not as bare top-level identifiers.
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
                    val parsed = OclLikeExpressionParser.tryParse(input = cleaned)
                    if (parsed != null) {
                        parsedCache[cleaned] = parsed
                    } else {
                        unparseable.add(cleaned)
                    }
                    parsed
                }
            }

        return try {
            cached?.let { evaluateViaAst(parsed = it, context = context) }
                ?: evaluateLegacy(cleaned = cleaned, instance = instance, event = event)
        } catch (_: StackOverflowError) {
            // Neither front-end caps the length of an eagerly-parsed binary-operator
            // chain (OclLikeExpressionParser.MAX_NESTING_DEPTH only bounds bracket/
            // unary-operator nesting), so a guard with tens of thousands of chained
            // "&&"/"and" operands parses fine but overflows the stack during the
            // recursive tree-walking *evaluation*, not during parsing. Several call
            // sites (StateMachineRuntime's default constructor, RunSessionManager,
            // the MCP RuntimeSessionManager, TraceReplayer) invoke this evaluator
            // directly with no surrounding try/catch, so an uncaught
            // StackOverflowError here would abort the whole run instead of failing
            // just this one guard closed — parity with
            // dev.kuml.runtime.activity.ActivityGuardEvaluator's identical guard.
            GuardResult.Failed("Guard evaluation exceeded the maximum expression nesting depth")
        }
    }

    /**
     * AST evaluator path: only trust it when [ExpressionEvaluator] returns a
     * definite Boolean that is not built on missing data. Returns `null` to
     * mean "cannot decide — fall back to [evaluateLegacy]", mirroring
     * [dev.kuml.runtime.activity.ActivityGuardEvaluator]'s rule.
     *
     * Only a `true` result needs the extra [GuardAstTaint.referencesUnresolvedVariable]
     * check: `false` is already the safe/fail-closed direction (the transition
     * does not fire) regardless of whether a referenced variable was missing or
     * present-and-genuinely-`false`. A `null`/non-Boolean evaluation result and
     * any parse or evaluation failure are all treated as "try the legacy
     * dialect", not as a final answer, so navigation errors and unknown-path
     * guards keep behaving identically to V2.0.19.
     */
    private fun evaluateViaAst(
        parsed: KumlExpression,
        context: Map<String, Any?>,
    ): GuardResult? =
        try {
            when (ExpressionEvaluator.evaluate(expr = parsed, context = context)) {
                true ->
                    if (GuardAstTaint.referencesUnresolvedVariable(expr = parsed, env = context)) {
                        null
                    } else {
                        GuardResult.True
                    }
                false -> GuardResult.False
                else -> null
            }
        } catch (_: EvaluationException) {
            null
        }

    /**
     * Legacy OCL evaluation via [OclExpressions.evaluateTracked]. Called when
     * the typed-AST parser cannot handle the guard, or when AST evaluation does
     * not produce a definite Boolean.
     *
     * This is the V1.1.x–V2.0.19 implementation, extended the same way
     * [dev.kuml.runtime.activity.ActivityGuardEvaluator.evaluateViaOcl] is: a
     * `true` result built on a variable name missing from `env` (OCL's `<>` is
     * exactly as null-tolerant as the AST path's `!=`, and the dot-navigation
     * spelling used throughout guards — `vars.x <> 1`, `event.x <> 1` — resolves
     * against a `Map` receiver exactly as null-tolerant as a bare `env` lookup)
     * is downgraded to [GuardResult.Failed] instead of trusted, via
     * [OclExpressions.evaluateTracked] instead of the plain `evaluate`. There is
     * no further dialect to fall back to here, so that downgrade is a terminal
     * `Failed`, not a `null` deferral.
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
            val tracked = OclExpressions.evaluateTracked(expression = cleaned, self = instance, env = env)
            when (tracked.value) {
                true ->
                    if (tracked.referencedMissingVariable) {
                        GuardResult.Failed("Guard result depends on a variable that was not provided: $cleaned")
                    } else {
                        GuardResult.True
                    }
                false -> GuardResult.False
                null -> GuardResult.False
                else -> GuardResult.Failed("Guard expression did not evaluate to Boolean (got ${tracked.value})")
            }
        } catch (ex: OclEvaluationException) {
            GuardResult.Failed(ex.message ?: ex.javaClass.simpleName)
        } catch (_: InterruptedException) {
            // Do not swallow an interrupt: a caller wrapping this evaluator in
            // dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator cancels the worker
            // thread running this method on timeout. Restoring the flag lets any
            // interruptible code further up this thread's call stack observe it
            // instead of the interrupt being silently discarded here.
            Thread.currentThread().interrupt()
            GuardResult.Failed("Guard evaluation was interrupted")
        } catch (ex: IllegalArgumentException) {
            GuardResult.Failed("Guard parse error: ${ex.message ?: ex.javaClass.simpleName}")
        } catch (ex: IllegalStateException) {
            GuardResult.Failed("Guard error: ${ex.message ?: ex.javaClass.simpleName}")
        } catch (ex: RuntimeException) {
            // Catch-all required by this class's "never throws" contract (see
            // StateMachineRuntime's default constructor, RunSessionManager, the
            // MCP RuntimeSessionManager, and TraceReplayer, none of which wrap this
            // evaluator in a try/catch of their own). dev.kuml.core.ocl's
            // OclEvaluator throws plain unchecked RuntimeExceptions the more
            // specific catches above are not declared to handle — e.g.
            // `expr.args.first()` in evalCollectionOp throws NoSuchElementException
            // for a syntactically valid but argument-less call the parser accepts
            // (`x->includes()`), and `expr.body!!` in the forAll/exists helper
            // throws NullPointerException when body-less iterator syntax is used
            // (`x->forAll(1)`). Both must still fail closed as GuardResult.Failed
            // instead of propagating out of evaluate() and aborting the caller's
            // run — parity with ActivityGuardEvaluator.evaluateViaOcl's identical
            // catch-all.
            GuardResult.Failed("Guard evaluation error: ${ex.message ?: ex.javaClass.simpleName}")
        }

    private fun stripBrackets(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("[") && t.endsWith("]")) {
            t.substring(1, t.length - 1).trim()
        } else {
            t
        }
    }
}
