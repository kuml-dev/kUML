package dev.kuml.runtime.activity

import dev.kuml.core.ocl.OclEvaluationException
import dev.kuml.core.ocl.OclExpressions
import dev.kuml.expr.EvaluationException
import dev.kuml.expr.ExpressionEvaluator
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.runtime.internal.GuardAstTaint

/**
 * Default [GuardEvaluator] for [ActivityRuntime] token-flow guards (ADR-0015 /
 * security fix B2).
 *
 * Extracted from the (previously dead) `ActivityRuntime.evaluateGuard` env-building
 * logic so it can be shared with `kuml-runtime-tokenflow`'s `TokenFlowGuardEvaluator`
 * and — critically — so it can be wrapped by
 * `dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator` by callers that need a
 * bounded guard-evaluation time budget (`kuml simulate --sandbox`, `kuml run`,
 * the MCP `kuml.run.*` tools).
 *
 * Before this fix, [ActivityRuntime] declared a `guardEvaluator` constructor
 * parameter but never actually invoked it — `evaluateGuard` called
 * `OclExpressions.evaluate` directly, so a `TimeLimitedGuardEvaluator` injected by
 * a caller had no effect and a malicious/buggy guard expression could hang
 * activity execution indefinitely. This class restores the exact historical
 * evaluation semantics (bare-identifier guards like `"allow"` / `"!allow"`
 * resolve directly against the event context) while making the evaluator
 * actually pluggable.
 *
 * ## Two-path dialect (bugfix: `!`-negation was silently `false`)
 *
 * Like [dev.kuml.runtime.OclGuardEvaluator] on the STM path, this evaluator tries
 * a typed-AST front-end first ([OclLikeExpressionParser] + [ExpressionEvaluator])
 * and falls back to the legacy `dev.kuml.core.ocl` front-end
 * ([OclExpressions]) when the AST parser cannot handle the input or does not
 * produce a definite Boolean. This gives guards on the Activity/BPMN path the
 * same two operator dialects the STM path already had:
 *
 *  - `dev.kuml.core.ocl` (OCL keywords): `not`, `and`, `or`, `<>` — `!` is a
 *    lexer error here.
 *  - `dev.kuml.expr` (C-like): `!`, `&&`, `||`, `!=` — `not`/`and`/`or`/`<>`
 *    are not tokens here and fail to parse, triggering the fallback.
 *
 * Before this fix, `ActivityGuardEvaluator` used *only* the `dev.kuml.core.ocl`
 * front-end, so `!allow`-style guards — used throughout the shipped examples,
 * the handbook, and the vault — silently evaluated to `false` on every input
 * (a dead branch), even though the identical guard already worked correctly on
 * the STM path via [dev.kuml.runtime.OclGuardEvaluator]. Note that `!` and
 * `not` have the *same* precedence in their respective grammars — both bind
 * looser than comparison, so `!a == b` and `not a = b` both mean `!(a == b)` /
 * `not (a = b)`, never `(!a) == b`. The two front-ends differ only in which
 * operator *tokens* they lex (`!`/`&&`/`||`/`!=` vs. `not`/`and`/`or`/`<>`),
 * not in how they bind — this is why negation is handled by trying a whole
 * second parser/evaluator, not by rewriting `"!x"` to `"not (x)"` as a string.
 *
 * Unlike [dev.kuml.runtime.OclGuardEvaluator], this evaluator does **not**
 * cache parsed expressions — guard strings here are typically synthesized
 * per-call from small, hand-authored models, and parsing is cheap; adding an
 * unbounded cache would be a new unbounded memory-growth surface in exactly
 * the evaluation chain that was just hardened against DoS
 * ([dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator]).
 *
 * ## Evaluation environment
 *
 * Bare-identifier guards must keep working (`ActivityRuntimeTest`,
 * `Sysml2ActivityAdapterTest`, and the BPMN OKF examples all rely on this), so
 * the [instance]'s `variables` map is merged directly into the evaluation
 * env at the top level, and is also exposed under the conventional `"event"`
 * and `"vars"` keys for guards written in the `event.foo` / `vars.foo` style
 * used by the STM side. This env-building is unchanged by this fix.
 *
 * ## Semantics after the fix (fail-closed is preserved)
 *
 * | Guard | variable | Result |
 * |---|---|---|
 * | `null` / blank | — | `True` |
 * | `allow` | `true` | `True` |
 * | `allow` | `false` | `False` |
 * | `allow` | missing | `False` |
 * | `!allow` | `false` | `True` — the fix |
 * | `!allow` | `true` | `False` |
 * | `!allow` | missing | `Failed` — fail-closed (never `True`): `NOT(null)` throws on the AST path,
 *   and the OCL fallback cannot lex `!` at all either, so this surfaces as a diagnosable
 *   failure rather than a silent `False` (same outcome [dev.kuml.runtime.OclGuardEvaluator]
 *   already had for this exact case on the STM path) |
 * | `not allow` | `false` | `True` — unchanged, routes through the OCL front-end |
 * | `x != 1` | missing | `Failed` — fail-closed, see [GuardAstTaint.referencesUnresolvedVariable] |
 * | `x <> 1` (OCL spelling of `!=`) | missing | `Failed` — fail-closed, same reasoning applied to the OCL front-end, see [evaluateViaOcl] |
 * | `foo() != 1` (any guard containing a function call) | — | `Failed` — fail-closed; function calls are never resolved by [ExpressionEvaluator], see [GuardAstTaint.referencesUnresolvedVariable] |
 * | `not (vars.a and vars.b)` (OCL front-end) | `a=true`, `b` present but non-Boolean | `Failed` — fail-closed; `true and <non-Boolean>` is an unresolved OCL truth value ("invalid"), not a trustworthy `false`, so the negation cannot become an untainted `True` — see the three-valued truth table in `OclEvaluator.evalBinaryOp` |
 * | `42` (non-boolean) | — | `Failed` |
 * | genuine syntax error | — | `Failed` — a parse failure is now distinguishable from a legitimate `false`.
 *   Whether that reaches the caller depends on the caller: via
 *   `dev.kuml.runtime.tokenflow.TokenFlowGuardEvaluator` → `TokenFlowEngine.guardResultListener`
 *   (surfaced as a `GUARD_EVALUATION_FAILED` warning by `kuml simulate`); via [ActivityRuntime]
 *   directly, not at all. Branch selection is unaffected either way, because callers only
 *   compare `== GuardResult.True`
 * | a guard whose parsed AST has thousands of chained `&&`/`||`/`and`/`or` operands | — | `Failed` — fail-closed; see the `StackOverflowError` catch in [evaluate] |
 *
 * ## Fail-closed comparisons against a missing variable
 *
 * [ExpressionEvaluator]'s `==`/`!=` are null-tolerant: a missing attribute
 * resolves to `null` exactly like an attribute that is present but genuinely
 * `null`, so `x != 1` with a *missing* `x` evaluates to a definite `true`
 * instead of signalling "unknown" the way a bare missing identifier does
 * (`raw == null` there is caught and routed to the OCL fallback). Left
 * unchecked this would make a guard fire on data that was never set —
 * exactly the class of silent-wrong-branch bug this evaluator exists to
 * close, only inverted (fail-*open* instead of fail-closed). [evaluateViaAst]
 * therefore only trusts a `true` AST result once
 * [GuardAstTaint.referencesUnresolvedVariable] confirms every variable the
 * expression *actually visited* (respecting `&&`/`||` short-circuiting) was
 * present; a `true` built on a missing variable is downgraded to "cannot
 * decide" and deferred to the OCL fallback, which for `!=`/`!` — characters
 * the OCL lexer cannot tokenize at all — surfaces as [GuardResult.Failed],
 * same as `!<missing>` above. A `false` AST result is trusted unchanged: it
 * is already the safe direction (the edge is not taken) whether the variable
 * was missing or present-and-genuinely-`false`, so no extra check is needed
 * or performed there. See [GuardAstTaint] for the full reasoning (including
 * the `FunctionCall` and short-circuit handling) — this evaluator and
 * [dev.kuml.runtime.OclGuardEvaluator] share that single implementation so
 * the AST-path check cannot again drift out of sync between the two guard
 * evaluators the way it did before this class existed.
 *
 * The identical fail-open shape exists on the OCL front-end too: OCL's `<>`
 * (the keyword-dialect spelling of `!=`) is evaluated by
 * `dev.kuml.core.ocl.OclEvaluator`, whose `env`-bound variable lookups
 * (`VarRef`) are just as null-tolerant as [ExpressionEvaluator]'s
 * `AttributeRef`s — and so is the dot-navigation spelling used throughout
 * guards (`vars.status <> 'x'`, `event.status <> 'x'`), which resolves
 * against a `Map` receiver exactly as null-tolerant as an `env` lookup.
 * [evaluateViaOcl] closes both spellings the same way, via
 * `OclExpressions.evaluateTracked` instead of the plain `evaluate`.
 */
public class ActivityGuardEvaluator : GuardEvaluator {
    override fun evaluate(
        guard: String?,
        instance: ModelInstance<*>,
        event: Event,
    ): GuardResult {
        if (guard.isNullOrBlank()) return GuardResult.True

        val cleaned = stripBrackets(raw = guard)
        val env = buildEnv(instance = instance)

        return try {
            evaluateViaAst(cleaned = cleaned, env = env)
                ?: evaluateViaOcl(cleaned = cleaned, instance = instance, env = env)
        } catch (_: StackOverflowError) {
            // Neither front-end caps the length of an eagerly-parsed binary-operator
            // chain — OclLikeExpressionParser.MAX_NESTING_DEPTH only bounds bracket/
            // unary-operator nesting ('(', '!', unary '-'), while parseOr/parseAnd
            // parse an arbitrarily long "&&"/"||" chain iteratively at depth 0. The
            // legacy dev.kuml.core.ocl front-end's "and"/"or" chains have the same
            // shape. Either way, a guard with tens of thousands of chained operands
            // parses fine but overflows the stack during the recursive tree-walking
            // *evaluation*, not during parsing — so it cannot be caught by
            // OclLikeExpressionParser.tryParse's own safeguard. This class's
            // documented "never throws" contract (see evaluateViaOcl KDoc) would
            // otherwise only hold on the sandboxed path (TimeLimitedGuardEvaluator's
            // FutureTask wraps a worker Error into ExecutionException); the direct,
            // unsandboxed ActivityRuntime.evaluateGuard call site has no try/catch of
            // its own, so an uncaught StackOverflowError here would abort that
            // caller's run instead of failing just this one guard closed.
            GuardResult.Failed("Guard evaluation exceeded the maximum expression nesting depth")
        }
    }

    /** Flat env: variables at top level, plus the conventional "event"/"vars" views. */
    private fun buildEnv(instance: ModelInstance<*>): Map<String, Any?> {
        val context: Map<String, Any?> = instance.variables
        return context +
            mapOf(
                "event" to context,
                "vars" to context,
            )
    }

    /**
     * AST path (C-like dialect: `!`, `&&`, `||`, `!=`, ...).
     *
     * Returns `null` to mean "cannot decide — fall back to the OCL front-end",
     * mirroring [dev.kuml.runtime.OclGuardEvaluator]'s rule: only a definite
     * Boolean result is trusted from this path. A `null`/non-Boolean
     * evaluation result and any parse or evaluation failure are all treated
     * as "try the other dialect", not as a final answer.
     */
    private fun evaluateViaAst(
        cleaned: String,
        env: Map<String, Any?>,
    ): GuardResult? {
        val parsed = OclLikeExpressionParser.tryParse(input = cleaned) ?: return null
        return try {
            when (val raw = ExpressionEvaluator.evaluate(expr = parsed, context = env)) {
                // Only a `true` result needs the extra missing-variable check: `false`
                // is already the safe/fail-closed direction (the edge is not taken)
                // regardless of whether a referenced variable was missing or was
                // present-and-genuinely-false, exactly like the existing bare
                // "missing identifier -> False" convention below.
                true ->
                    if (GuardAstTaint.referencesUnresolvedVariable(expr = parsed, env = env)) {
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
    }

    /**
     * Legacy `dev.kuml.core.ocl` path. Called when the typed-AST parser cannot
     * handle the guard, or when AST evaluation does not produce a definite
     * Boolean. Preserved from the pre-fix implementation except for the
     * exception-to-[GuardResult] mapping (distinguishes a genuine
     * parse/evaluation failure, [GuardResult.Failed], from a legitimate
     * `false` result — a parse error being silently indistinguishable from
     * "guard is false" is the root cause the `!`-negation bug slipped through
     * unnoticed for as long as it did) and — like [evaluateViaAst] — the use
     * of `OclExpressions.evaluateTracked` instead of the plain `evaluate`, so
     * a `true` result built on a variable name missing from [env] (OCL's
     * `<>` is exactly as null-tolerant as the AST path's `!=`; see the class
     * KDoc) is downgraded to [GuardResult.Failed] instead of trusted. Unlike
     * [evaluateViaAst], there is no further dialect to fall back to here, so
     * that downgrade is a terminal `Failed`, not a `null` deferral.
     */
    private fun evaluateViaOcl(
        cleaned: String,
        instance: ModelInstance<*>,
        env: Map<String, Any?>,
    ): GuardResult =
        try {
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
                else -> GuardResult.Failed("Guard did not evaluate to Boolean (got ${tracked.value})")
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
            // ActivityRuntime.evaluateGuard, which has no try/catch of its own on
            // the direct, unsandboxed ActivityRuntime path). dev.kuml.core.ocl's
            // OclEvaluator throws plain unchecked RuntimeExceptions the more
            // specific catches above are not declared to handle — e.g.
            // `expr.args.first()` in evalCollectionOp throws NoSuchElementException
            // for a syntactically valid but argument-less call the parser accepts
            // (`x->includes()`), and `expr.body!!` in the forAll/exists helper
            // throws NullPointerException when body-less iterator syntax is used
            // (`x->forAll(1)`). Both must still fail closed as GuardResult.Failed
            // instead of propagating out of evaluate() and aborting the caller's
            // run.
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
