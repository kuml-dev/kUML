package dev.kuml.runtime.internal

import dev.kuml.expr.AttributeRef
import dev.kuml.expr.BinaryOp
import dev.kuml.expr.BinaryOperator
import dev.kuml.expr.ExpressionEvaluator
import dev.kuml.expr.FunctionCall
import dev.kuml.expr.KumlExpression
import dev.kuml.expr.LiteralBool
import dev.kuml.expr.LiteralInt
import dev.kuml.expr.LiteralNull
import dev.kuml.expr.LiteralReal
import dev.kuml.expr.LiteralString
import dev.kuml.expr.UnaryOp

/**
 * Short-circuit-aware "did this AST evaluation touch data that was never
 * provided?" check for the typed [KumlExpression] AST, shared by
 * [dev.kuml.runtime.OclGuardEvaluator] (STM guards) and
 * [dev.kuml.runtime.activity.ActivityGuardEvaluator] (Activity/BPMN/token-flow
 * guards).
 *
 * Extracted (verbatim, no logic change) from `ActivityGuardEvaluator` so both
 * guard evaluators share exactly one implementation of this check — the two
 * evaluators previously carried independent copies of the underlying
 * fail-open bug (missing-variable comparisons trusted as a definite `true`),
 * and only one of them had been fixed. Duplicating this logic a second time
 * would reproduce precisely that failure mode; both evaluators now delegate
 * here instead.
 *
 * ## Fail-closed comparisons against a missing variable
 *
 * [ExpressionEvaluator]'s `==`/`!=` are null-tolerant: a missing attribute
 * resolves to `null` exactly like an attribute that is present but genuinely
 * `null`, so `x != 1` with a *missing* `x` evaluates to a definite `true`
 * instead of signalling "unknown" the way a bare missing identifier does. Left
 * unchecked this would make a guard fire on data that was never set — exactly
 * the class of silent-wrong-branch bug the callers of this object exist to
 * close, only inverted (fail-*open* instead of fail-closed). Callers therefore
 * only trust a `true` AST result once [referencesUnresolvedVariable] confirms
 * every variable the expression *actually visited* (respecting `&&`/`||`
 * short-circuiting, see below) was present; a `true` built on a missing
 * variable is downgraded to "cannot decide" and deferred to the caller's OCL
 * fallback. A `false` AST result is trusted unchanged: it is already the safe
 * direction (the edge is not taken) whether the variable was missing or
 * present-and-genuinely-`false`, so no extra check is needed or performed
 * there.
 *
 * A [FunctionCall] is a third source of the same fail-open shape:
 * [ExpressionEvaluator.evaluate] resolves *every* [FunctionCall] to `null`
 * unconditionally (function resolution is not implemented yet), which is
 * indistinguishable from a missing variable for the purposes of a
 * null-tolerant `!=`/`==`. [referencesUnresolvedVariable] therefore treats any
 * [FunctionCall] the same way it treats an unresolved [AttributeRef] — as
 * "cannot decide" — regardless of what its arguments resolve to.
 *
 * ## Short-circuit-aware missing-variable check
 *
 * [ExpressionEvaluator.evalBinary] short-circuits `&&`/`||`: the right operand
 * of `isVip || spendOver1000` is never evaluated when `isVip` is already
 * `true`. [referencesUnresolvedVariable] mirrors this exactly (re-deriving
 * which branch a real evaluation would have taken from the *actual* value of
 * the left operand under the same `env`) instead of statically walking the
 * whole parsed tree — a static walk would flag a missing `spendOver1000` even
 * though it was never touched, downgrading a legitimately-`true` guard to a
 * spurious failure. Every other binary operator is eager in
 * [ExpressionEvaluator] (both sides are always evaluated), so both operands
 * are still checked unconditionally for those.
 *
 * ## O(n) re-derivation of the AND/OR spine — not O(n²)
 *
 * [OclLikeExpressionParser.parseAnd]/`parseOr` build a left-associative chain
 * for a run of `&&`/`||` (`a && a && ... && a` becomes
 * `(((a && a) && a) ... && a)`, depth == chain length — parsed via an
 * iterative loop, not recursion, so it is *not* bounded by
 * [OclLikeExpressionParser.MAX_NESTING_DEPTH], which only counts recursive
 * descent through `!`, unary `-`, and `(`). An earlier revision of this
 * short-circuit re-derivation called [ExpressionEvaluator.evaluate] on
 * [BinaryOp.left] *again* at every level of that chain to decide which branch
 * a real evaluation took, while *also* recursing into that same `left` for
 * the taint check — for a chain of length n that walks subtrees of size
 * n-1, n-2, ..., 1, i.e. O(n²) total, turning a single ~200-byte guard string
 * into hundreds of milliseconds of server CPU with no bound (the timeout in
 * [dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator] only frees the calling
 * thread — cancelling a CPU-bound, non-blocking recursive walk via
 * `Future.cancel(true)` does not stop it, since nothing here ever checks
 * [Thread.isInterrupted]).
 *
 * [evaluateAndTaint] fixes this by computing the value **and** the taint
 * verdict for the AND/OR spine together, bottom-up, in a single pass — each
 * node of the chain is visited exactly once, restoring O(n). It only ever
 * recurses along further `AND`/`OR` [BinaryOp] nodes (however they are
 * nested — a plain chain, buried under parentheses, or under a unary `!`);
 * anything else (a comparison, an [AttributeRef], a literal, an eager
 * arithmetic op, ...) is a leaf for its purposes, evaluated/tainted exactly
 * once via the ordinary, non-memoizing path below — which is safe because
 * such a leaf cannot itself be re-visited from multiple ancestor levels (its
 * own `&&`/`||` sub-chains, if any, are only reachable through this same
 * `is BinaryOp` dispatch and so are still handled by [evaluateAndTaint]).
 */
internal object GuardAstTaint {
    /**
     * `true` if [expr], evaluated the same way [ExpressionEvaluator.evaluate]
     * actually evaluates it (including `&&`/`||` short-circuiting — see
     * [evaluateAndTaint]), touches an [AttributeRef] whose path does not fully
     * resolve against [env] (the variable is *missing*, not merely
     * present-and-`null`) or a [FunctionCall] anywhere (which
     * [ExpressionEvaluator] always resolves to `null`, indistinguishable from
     * a missing variable).
     */
    internal fun referencesUnresolvedVariable(
        expr: KumlExpression,
        env: Map<String, Any?>,
    ): Boolean =
        when (expr) {
            is AttributeRef -> isUnresolved(ref = expr, env = env)
            is UnaryOp -> referencesUnresolvedVariable(expr = expr.operand, env = env)
            is BinaryOp ->
                when (expr.op) {
                    // Only AND/OR need the O(n) value+taint co-computation: they are
                    // the sole short-circuiting operators, and therefore the only
                    // shape ([OclLikeExpressionParser]'s left-associative chain of
                    // them) an attacker can grow unboundedly without tripping
                    // MAX_NESTING_DEPTH. See evaluateAndTaint's KDoc.
                    BinaryOperator.AND, BinaryOperator.OR -> evaluateAndTaint(expr = expr, env = env).second
                    // Every other operator is eager: both operands are always
                    // visited by ExpressionEvaluator, no value is needed to decide
                    // that, and unlike AND/OR this shape cannot grow into an
                    // unbounded left-recursive chain of *this exact node* (repeated
                    // re-evaluation of a growing subtree, i.e. the O(n²) this KDoc
                    // describes), because parseAdd/parseCompare/etc. are not
                    // reachable from parseAnd/parseOr except through a single
                    // AND/OR-free term — any further AND/OR nested underneath
                    // (necessarily via parentheses, since the grammar only makes
                    // AND/OR reachable there) is still routed back into this same
                    // `is BinaryOp` dispatch when this recursion reaches it.
                    else ->
                        referencesUnresolvedVariable(expr = expr.left, env = env) ||
                            referencesUnresolvedVariable(expr = expr.right, env = env)
                }
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
     * Computes, in one bottom-up pass, the pair (value ExpressionEvaluator
     * would produce for [expr], taint verdict [referencesUnresolvedVariable]
     * would produce for [expr]) — see this file's "O(n) re-derivation of the
     * AND/OR spine" KDoc for why the two must be computed together rather
     * than via two separate top-down walks.
     *
     * Mirrors [ExpressionEvaluator.evalBinary]'s exact AND/OR short-circuit
     * semantics:
     *  - `OR` with `left == true`: overall value is `true`; only `left` was
     *    visited, so overall taint is just `left`'s.
     *  - `OR` with `left != true`: overall value/taint are exactly `right`'s
     *    (this mirrors evalBinary's "return right's Boolean" — `left`'s
     *    `false`/unresolved is already the safe/trusted direction, so it does
     *    not contribute to taint here, matching the original recursive
     *    formulation this replaces).
     *  - `AND` with `left == false`: overall value is `false`; only `left` was
     *    visited (defensive — unreachable when the *caller* already knows the
     *    overall result is `true`, since that requires `left == true`, but
     *    kept for a `false`-valued subexpression reached while visiting an
     *    outer `OR`'s `right`, e.g. under a `!(...)` this function does not
     *    itself special-case).
     *  - `AND` otherwise: overall value is `right`'s; both operands were
     *    visited, so overall taint is `left`'s OR `right`'s.
     *  - any other [KumlExpression]: not itself a short-circuiting AND/OR
     *    node, so it is evaluated/tainted exactly once via the ordinary
     *    (non-memoizing) [ExpressionEvaluator.evaluate] /
     *    [referencesUnresolvedVariable] pair — safe per this file's "O(n)
     *    re-derivation" KDoc.
     *
     * Re-evaluating any subexpression here cannot itself throw: this function
     * is only ever reached (transitively) from the caller's `true` branch,
     * i.e. after [ExpressionEvaluator.evaluate] already evaluated this exact
     * subexpression, against this exact (immutable) [env], without throwing.
     */
    private fun evaluateAndTaint(
        expr: KumlExpression,
        env: Map<String, Any?>,
    ): Pair<Any?, Boolean> =
        if (expr is BinaryOp && expr.op == BinaryOperator.OR) {
            val (leftValue, leftTaint) = evaluateAndTaint(expr = expr.left, env = env)
            if (leftValue == true) true to leftTaint else evaluateAndTaint(expr = expr.right, env = env)
        } else if (expr is BinaryOp && expr.op == BinaryOperator.AND) {
            val (leftValue, leftTaint) = evaluateAndTaint(expr = expr.left, env = env)
            if (leftValue == false) {
                false to leftTaint
            } else {
                val (rightValue, rightTaint) = evaluateAndTaint(expr = expr.right, env = env)
                rightValue to (leftTaint || rightTaint)
            }
        } else {
            ExpressionEvaluator.evaluate(expr = expr, context = env) to referencesUnresolvedVariable(expr = expr, env = env)
        }

    /** Mirrors [ExpressionEvaluator]'s own path-navigation, but reports "not present" explicitly. */
    internal fun isUnresolved(
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
}
