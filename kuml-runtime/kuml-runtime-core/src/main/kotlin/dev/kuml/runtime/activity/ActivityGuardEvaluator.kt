package dev.kuml.runtime.activity

import dev.kuml.core.ocl.OclEvaluationException
import dev.kuml.core.ocl.OclExpressions
import dev.kuml.expr.AttributeRef
import dev.kuml.expr.BinaryOp
import dev.kuml.expr.BinaryOperator
import dev.kuml.expr.EvaluationException
import dev.kuml.expr.ExpressionEvaluator
import dev.kuml.expr.FunctionCall
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.LiteralBool
import dev.kuml.expr.LiteralInt
import dev.kuml.expr.LiteralNull
import dev.kuml.expr.LiteralReal
import dev.kuml.expr.LiteralString
import dev.kuml.expr.OclLikeExpressionParser
import dev.kuml.expr.UnaryOp
import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardEvaluator
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance

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
 * | `x != 1` | missing | `Failed` — fail-closed, see [referencesUnresolvedVariable] below |
 * | `x <> 1` (OCL spelling of `!=`) | missing | `Failed` — fail-closed, same reasoning applied to the OCL front-end, see [evaluateViaOcl] |
 * | `foo() != 1` (any guard containing a function call) | — | `Failed` — fail-closed; function calls are never resolved by [ExpressionEvaluator], see [referencesUnresolvedVariable] |
 * | `not (vars.a and vars.b)` (OCL front-end) | `a=true`, `b` present but non-Boolean | `Failed` — fail-closed; `true and <non-Boolean>` is an unresolved OCL truth value ("invalid"), not a trustworthy `false`, so the negation cannot become an untainted `True` — see the three-valued truth table in `OclEvaluator.evalBinaryOp` |
 * | `42` (non-boolean) | — | `Failed` |
 * | genuine syntax error | — | `Failed` — a parse failure is now distinguishable from a legitimate `false`
 *   (surfaced to the caller as a `GUARD_EVALUATION_FAILED` warning via the existing
 *   `TokenFlowEngine.guardResultListener` side-channel; branch selection is unaffected
 *   because callers only compare `== GuardResult.True`)
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
 * therefore only trusts a `true` AST result once [referencesUnresolvedVariable]
 * confirms every variable the expression *actually visited* (respecting
 * `&&`/`||` short-circuiting, see below) was present; a `true` built on a
 * missing variable is downgraded to "cannot decide" and deferred to the OCL
 * fallback, which for `!=`/`!` — characters the OCL lexer cannot tokenize at
 * all — surfaces as [GuardResult.Failed], same as `!<missing>` above. A
 * `false` AST result is trusted unchanged: it is already the safe direction
 * (the edge is not taken) whether the variable was missing or
 * present-and-genuinely-`false`, so no extra check is needed or performed
 * there.
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
 *
 * A [FunctionCall] is a third source of the same fail-open shape:
 * [ExpressionEvaluator.evaluate] resolves *every* [FunctionCall] to `null`
 * unconditionally (function resolution is not implemented yet), which is
 * indistinguishable from a missing variable for the purposes of a
 * null-tolerant `!=`/`==`. [referencesUnresolvedVariable] therefore treats
 * any [FunctionCall] the same way it treats an unresolved [AttributeRef] —
 * as "cannot decide" — regardless of what its arguments resolve to.
 *
 * ## Short-circuit-aware missing-variable check
 *
 * [ExpressionEvaluator.evalBinary] short-circuits `&&`/`||`: the right
 * operand of `isVip || spendOver1000` is never evaluated when `isVip` is
 * already `true`. [referencesUnresolvedVariable] mirrors this exactly
 * (re-deriving which branch a real evaluation would have taken from the
 * *actual* value of the left operand under the same `env`) instead of
 * statically walking the whole parsed tree — a static walk would flag a
 * missing `spendOver1000` even though it was never touched, downgrading a
 * legitimately-`true` guard to a spurious `GUARD_EVALUATION_FAILED` warning.
 * Every other binary operator is eager in [ExpressionEvaluator] (both sides
 * are always evaluated), so both operands are still checked unconditionally
 * for those.
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
                true -> if (referencesUnresolvedVariable(expr = parsed, env = env)) null else GuardResult.True
                false -> GuardResult.False
                else -> null
            }
        } catch (_: EvaluationException) {
            null
        }
    }

    /**
     * `true` if [expr], evaluated the same way [ExpressionEvaluator.evaluate]
     * actually evaluates it (including `&&`/`||` short-circuiting — see
     * [referencesUnresolvedVariableInBinary]), touches an [AttributeRef]
     * whose path does not fully resolve against [env] (the variable is
     * *missing*, not merely present-and-`null`) or a [FunctionCall] anywhere
     * (which [ExpressionEvaluator] always resolves to `null`, indistinguishable
     * from a missing variable). See the class KDoc sections "Fail-closed
     * comparisons against a missing variable" and "Short-circuit-aware
     * missing-variable check" for why this matters: without it,
     * [ExpressionEvaluator]'s null-tolerant `==`/`!=` would let a comparison
     * against a missing variable, or against an unresolved function call,
     * return a trusted (and wrong) definite Boolean instead of falling back.
     */
    private fun referencesUnresolvedVariable(
        expr: KumlExpression,
        env: Map<String, Any?>,
    ): Boolean =
        when (expr) {
            is AttributeRef -> isUnresolved(ref = expr, env = env)
            is UnaryOp -> referencesUnresolvedVariable(expr = expr.operand, env = env)
            is BinaryOp -> referencesUnresolvedVariableInBinary(expr = expr, env = env)
            // ExpressionEvaluator.evaluate resolves every FunctionCall to `null`
            // unconditionally (function resolution is not implemented — see
            // ExpressionEvaluator's "V2.0.20b adds function resolution" comment),
            // so a FunctionCall anywhere in the expression makes the surrounding
            // comparison exactly as untrustworthy as a missing variable would —
            // regardless of what its own arguments resolve to.
            is FunctionCall -> true
            is LiteralBool, is LiteralInt, is LiteralReal, is LiteralString, LiteralNull -> false
        }

    /**
     * Short-circuit-aware [referencesUnresolvedVariable] for [BinaryOp]s.
     *
     * [ExpressionEvaluator.evalBinary] short-circuits `OR`/`AND`: the right
     * operand is only evaluated when the left one does not already decide
     * the result (`left == true` for `OR`, `left == false` for `AND`). A
     * static "check both sides unconditionally" walk would flag a missing
     * variable on a branch a real evaluation never touches — e.g.
     * `isVip || spendOver1000` with `isVip == true` and `spendOver1000`
     * missing is a legitimate, trustworthy `true` (the right side is never
     * evaluated), but a static walk would downgrade it to "cannot decide"
     * anyway. This re-derives the same branch a real evaluation would take
     * (by re-evaluating the left operand against the same, side-effect-free
     * [env] — cheap, since guard expressions are small) and only recurses
     * into the operand(s) that were actually visited:
     *  - `OR` with `left == true`: only `left` was visited.
     *  - `OR` with `left != true` (so `left` is `false`, or unresolved and
     *    thus not a definite Boolean by [ExpressionEvaluator]'s own rules):
     *    a `true` overall result can only come from `right`, and `left`'s
     *    `false` is already the safe/trusted direction per the class KDoc
     *    ("Fail-closed comparisons against a missing variable"), so only
     *    `right` needs checking.
     *  - `AND` with `left == false`: only `left` was visited (defensive —
     *    unreachable when the caller already knows the overall `AND` result
     *    is `true`, since that requires `left == true`).
     *  - `AND` otherwise: both operands were visited and both must be
     *    trustworthy for the `true` result to be trustworthy.
     *  - every other operator (`==`, `!=`, `<`, ...): eager in
     *    [ExpressionEvaluator] — both operands are always visited.
     *
     * Re-evaluating [BinaryOp.left] here cannot itself throw: this function
     * is only ever reached (transitively) from [evaluateViaAst]'s `true`
     * branch, i.e. after [ExpressionEvaluator.evaluate] already evaluated
     * this exact subexpression, against this exact (immutable) [env],
     * without throwing.
     */
    private fun referencesUnresolvedVariableInBinary(
        expr: BinaryOp,
        env: Map<String, Any?>,
    ): Boolean {
        fun left() = referencesUnresolvedVariable(expr = expr.left, env = env)

        fun right() = referencesUnresolvedVariable(expr = expr.right, env = env)

        return when (expr.op) {
            BinaryOperator.OR ->
                if (ExpressionEvaluator.evaluate(expr = expr.left, context = env) == true) left() else right()
            BinaryOperator.AND ->
                if (ExpressionEvaluator.evaluate(expr = expr.left, context = env) == false) left() else left() || right()
            else -> left() || right()
        }
    }

    /** Mirrors [ExpressionEvaluator]'s own path-navigation, but reports "not present" explicitly. */
    private fun isUnresolved(
        ref: AttributeRef,
        env: Map<String, Any?>,
    ): Boolean {
        if (ref.path.isEmpty()) return false
        if (!env.containsKey(ref.path[0])) return true
        var current: Any? = env[ref.path[0]]
        for (i in 1 until ref.path.size) {
            val map = current as? Map<*, *> ?: return true
            if (!map.containsKey(ref.path[i])) return true
            current = map[ref.path[i]]
        }
        return false
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
