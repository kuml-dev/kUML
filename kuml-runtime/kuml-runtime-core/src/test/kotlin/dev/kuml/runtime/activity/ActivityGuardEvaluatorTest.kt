package dev.kuml.runtime.activity

import dev.kuml.runtime.Event
import dev.kuml.runtime.GuardResult
import dev.kuml.runtime.ModelInstance
import dev.kuml.runtime.TraceEntry
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [ActivityGuardEvaluator] (ADR-0015 / security fix B2).
 *
 * Before this class existed, `ActivityRuntime.evaluateGuard` called
 * `OclExpressions.evaluate` directly and never consulted the injected
 * `guardEvaluator` at all — a `TimeLimitedGuardEvaluator` wrapping it had no
 * effect. [ActivityRuntimeTest] ("wired guard evaluator" tests below) covers
 * the regression at the `ActivityRuntime` integration level; this file covers
 * the evaluator's own evaluation-environment contract in isolation.
 */
private class TestModelInstance(
    variables: Map<String, Any?>,
) : ModelInstance<Any> {
    override val model: Any get() = error("TestModelInstance.model should not be accessed")
    override val currentVertices: List<dev.kuml.uml.UmlVertex> = emptyList()
    override val variables: MutableMap<String, Any?> = variables.toMutableMap()
    override val isTerminated: Boolean = false
    override val trace: List<TraceEntry> = emptyList()
}

class ActivityGuardEvaluatorTest :
    FunSpec({

        val ev = ActivityGuardEvaluator()

        fun instance(vars: Map<String, Any?> = emptyMap()) = TestModelInstance(vars)

        test("null guard returns True") {
            ev.evaluate(guard = null, instance = instance(), event = Event.of("advance")) shouldBe GuardResult.True
        }

        test("blank guard returns True") {
            ev.evaluate(guard = "   ", instance = instance(), event = Event.of("advance")) shouldBe GuardResult.True
        }

        test("bare identifier resolves directly against instance variables (legacy semantics)") {
            ev.evaluate(guard = "allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("'not <identifier>' negates a bare identifier (OCL spells negation 'not', not '!')") {
            ev.evaluate(guard = "not allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "not allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("square brackets are stripped before parsing") {
            ev.evaluate(guard = "[allow]", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.True
        }

        test("event.<key> and vars.<key> access the same instance variables") {
            val i = instance(mapOf("flag" to true))
            ev.evaluate(guard = "event.flag", instance = i, event = Event.of("advance")) shouldBe GuardResult.True
            ev.evaluate(guard = "vars.flag", instance = i, event = Event.of("advance")) shouldBe GuardResult.True
        }

        test("missing variable is treated as false, not an exception") {
            ev.evaluate(guard = "missing", instance = instance(), event = Event.of("advance")) shouldBe GuardResult.False
        }

        test("a genuine syntax error is Failed, not silently False (never throws, per class contract)") {
            val result = ev.evaluate(guard = "!!! invalid !!!", instance = instance(), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("non-boolean result is treated as Failed") {
            val result = ev.evaluate(guard = "42", instance = instance(), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Regression: '!'-negation was silently always False ────────────────

        test("'!<identifier>' negates a bare identifier (the KDoc contract, regression for the silent-False bug)") {
            ev.evaluate(guard = "!allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "!allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("'!' and 'not' agree on a bare identifier") {
            for (allow in listOf(true, false)) {
                val i = instance(mapOf("allow" to allow))
                ev.evaluate(guard = "!allow", instance = i, event = Event.of("advance")) shouldBe
                    ev.evaluate(guard = "not allow", instance = i, event = Event.of("advance"))
            }
        }

        test("'not <identifier>' still routes through the OCL front-end and keeps working") {
            ev.evaluate(guard = "not allow", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "not allow", instance = instance(mapOf("allow" to true)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("'!' on a missing variable is fail-closed (never True)") {
            // The AST path throws on NOT(null) (a missing variable resolves to null,
            // and the unary '!' operator requires a definite Boolean), so this falls
            // back to the OCL front-end — which cannot lex '!' at all and reports
            // Failed. Either way the branch is never taken: fail-closed, just like a
            // missing bare identifier, but surfaced as a diagnosable Failed rather
            // than a silent False (parity with dev.kuml.runtime.OclGuardEvaluator,
            // whose fallback hits the exact same OCL-lexer wall for '!').
            val result = ev.evaluate(guard = "!missing", instance = instance(), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'[!allow]' — bracket stripping happens before negation") {
            ev.evaluate(guard = "[!allow]", instance = instance(mapOf("allow" to false)), event = Event.of("advance")) shouldBe
                GuardResult.True
        }

        test("'!' composes with && and ||") {
            ev.evaluate(
                guard = "!a && !b",
                instance = instance(mapOf("a" to false, "b" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
            ev.evaluate(
                guard = "!a && b",
                instance = instance(mapOf("a" to false, "b" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
            ev.evaluate(
                guard = "!a && b",
                instance = instance(mapOf("a" to true, "b" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        test("OCL-dialect operators still route to the OCL front-end") {
            ev.evaluate(
                guard = "a and b",
                instance = instance(mapOf("a" to true, "b" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
            ev.evaluate(
                guard = "a or b",
                instance = instance(mapOf("a" to false, "b" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
            ev.evaluate(
                guard = "a implies b",
                instance = instance(mapOf("a" to false, "b" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
            ev.evaluate(
                guard = "x <> 1",
                instance = instance(mapOf("x" to 2)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        // ── Regression: 'and' must not fail-closed on a missing left operand ────
        // when the right operand alone concretely decides the result (MAJOR
        // finding, branch fix/activity-guard-negation). Reproduced empirically:
        // `not (vars.approvalRequired and vars.approved)` with only `approved`
        // set to `false` returned `Failed` instead of `True` before this fix.

        test("'and' with a missing left operand and a concretely false right operand is True when negated (dot-navigation)") {
            ev.evaluate(
                guard = "not (vars.approvalRequired and vars.approved)",
                instance = instance(mapOf("approved" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'and' with a missing left operand and a concretely false right operand is True when negated (bare identifiers)") {
            ev.evaluate(
                guard = "not (approvalRequired and approved)",
                instance = instance(mapOf("approved" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        // ── Regression, follow-up: the 'and' arm's right-operand evaluation was ──
        // never wrapped in try/catch when the left operand was switched to
        // [dev.kuml.core.ocl.OclEvaluator.evalIsolated] (MAJOR finding, branch
        // fix/activity-guard-negation). A left operand that only touched a
        // missing variable (its own taint living solely in a local variable)
        // followed by a right operand that *throws* while being navigated
        // (swallowed by `closure(...)`'s "no successor" catch) silently
        // dropped that taint instead of preserving it — turning a fail-closed
        // guard into a fail-open one. Reproduced empirically: before this fix,
        // this guard returned `True` (the edge fired); it must return `Failed`.

        test("'and' with a missing left operand still fails closed when the right operand throws inside a closure") {
            val result =
                ev.evaluate(
                    guard = "vars.items->closure(c | vars.missing and c.bogusProp)->isEmpty()",
                    instance = instance(mapOf("items" to listOf(1, 2))),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Regression: 'oclIsUndefined()' must not fail-closed on the very ──────
        // absence it is checking for (MINOR finding, branch
        // fix/activity-guard-negation). Reproduced empirically:
        // `vars.cancelReason.oclIsUndefined()` with no `cancelReason` in
        // `instance.variables` returned `Failed` instead of `True` before this
        // fix.

        test("'oclIsUndefined()' on a genuinely absent variable is True, not Failed") {
            ev.evaluate(
                guard = "vars.cancelReason.oclIsUndefined()",
                instance = instance(mapOf("other" to 1)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        // ── Regression, follow-up: 'oclIsUndefined()'/'oclIsInvalid()' must NOT ──
        // discard receiver taint for a *computed* receiver (MINOR finding,
        // branch fix/activity-guard-negation). The fix above is correct only
        // for a plain lookup-chain receiver (`vars.cancelReason`, where "the
        // value is missing" genuinely is the trustworthy answer); a receiver
        // built from a collection operation depends on the missing data in a
        // way that must still taint the result. Here `any(...)` finds no
        // matching item (because `vars.target` was never provided) and
        // returns `null`, so `oclIsUndefined()` reads `true` — but that
        // `true` is only true because of the missing variable, so it must
        // stay tainted. Reproduced empirically: before this fix, this guard
        // returned `True` (the edge fired) regardless of whether `target`
        // was ever provided; it must return `Failed`.

        test(
            "'oclIsUndefined()' on a computed (non-lookup-chain) receiver stays fail-closed " +
                "when the result depends on a missing variable",
        ) {
            val result =
                ev.evaluate(
                    guard = "vars.items->any(i | i = vars.target).oclIsUndefined()",
                    instance = instance(mapOf("items" to listOf(1, 2, 3))),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'!=' works on the activity path (parity with the STM path)") {
            ev.evaluate(guard = "x != 1", instance = instance(mapOf("x" to 2)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "x != 1", instance = instance(mapOf("x" to 1)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("'!=' against a missing variable is fail-closed (never True), not a silent branch-take") {
            // ExpressionEvaluator's == / != are null-tolerant: a missing attribute
            // resolves to null exactly like one that is present-but-null, so without
            // the referencesUnresolvedVariable guard "status != 'DONE'" with a missing
            // "status" would evaluate to a trusted `true` on the AST path (NEQ of
            // false) and the decision edge would fire on data that was never set.
            // Must behave like the "!<missing>" case above: never True.
            val result = ev.evaluate(guard = "status != 'DONE'", instance = instance(), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'==' against a missing variable stays False, matching bare-missing-identifier semantics") {
            // A 'false' AST result is always trusted, missing variable or not: it is
            // already the safe/fail-closed direction (edge not taken), so the extra
            // referencesUnresolvedVariable check only ever applies to a 'true' result.
            ev.evaluate(guard = "status == 'DONE'", instance = instance(), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        // ── Null-comparison presence-check idiom: '<> null'/'!= null' contract ──
        // (Runde-2-Review-Befund: pins the documented "is X set?" idiom — missing
        // → False, present-and-non-null → True, present-and-null → False — on the
        // Activity/BPMN path too, parity with OclGuardEvaluatorTest.)

        test("'x <> null' presence-check idiom: missing, non-null, and null states") {
            ev.evaluate(guard = "x <> null", instance = instance(), event = Event.of("advance")) shouldBe
                GuardResult.False
            ev.evaluate(guard = "x <> null", instance = instance(mapOf("x" to 5)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "x <> null", instance = instance(mapOf("x" to null)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        test("'x != null' presence-check idiom: missing, non-null, and null states") {
            ev.evaluate(guard = "x != null", instance = instance(), event = Event.of("advance")) shouldBe
                GuardResult.False
            ev.evaluate(guard = "x != null", instance = instance(mapOf("x" to 5)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "x != null", instance = instance(mapOf("x" to null)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        // ── Mirror-image idiom: '= null'/'== null' ("is X *not* set?") IS a ──────
        // breaking change (Runde-2-Review-Befund 1), parity with
        // OclGuardEvaluatorTest's identical pin on the STM path. A missing 'x'
        // makes this comparison evaluate to a true built on data that was never
        // provided — exactly the shape the fail-closed rule downgrades. Before
        // this rule existed this was GuardResult.True; it must now be
        // GuardResult.Failed.

        test("'x = null' with missing 'x' is Failed, not True (breaking change vs. pre-fix behavior)") {
            val result = ev.evaluate(guard = "x = null", instance = instance(), event = Event.of("advance"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'x == null' with missing 'x' is Failed, not True (breaking change vs. pre-fix behavior)") {
            val result = ev.evaluate(guard = "x == null", instance = instance(), event = Event.of("advance"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'x == null' evaluates True when 'x' is present and genuinely null") {
            ev.evaluate(guard = "x == null", instance = instance(mapOf("x" to null)), event = Event.of("advance")) shouldBe
                GuardResult.True
        }

        // ── Negative / tamper tests (security-loop requirement) ───────────────

        test("a guard that is a huge chained '!' does not blow the stack") {
            val guard = "!".repeat(5000) + "allow"
            val result = ev.evaluate(guard = guard, instance = instance(mapOf("allow" to true)), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("a '!' inside a string literal is not mistaken for negation") {
            ev.evaluate(
                guard = "name = 'a!b'",
                instance = instance(mapOf("name" to "a!b")),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("evaluate never throws for a table of malicious/malformed guard strings") {
            val guards =
                listOf(
                    "!!! invalid !!!",
                    "!".repeat(5000) + "allow",
                    "(".repeat(5000),
                    "",
                    "   ",
                    "!=",
                    "&&",
                    "a b c",
                    // Reach OclEvaluator.evalCollectionOp: "->" is not a token the
                    // C-like AST parser recognizes at all, so these fall straight
                    // through to the legacy OCL front-end.
                    "x->includes()",
                    "x->forAll(1)",
                )
            for (guard in guards) {
                ev.evaluate(guard = guard, instance = instance(), event = Event.of("advance"))
            }
        }

        // ── Regression: uncaught RuntimeException from OclEvaluator ────────────

        test("'x->includes()' — a collection op the parser accepts with no argument — fails closed, never throws") {
            // OclParser.parseCollectionOp explicitly allows an empty argument list,
            // but OclEvaluator.evalCollectionOp's "includes" branch unconditionally
            // calls expr.args.first(), throwing NoSuchElementException. Before the
            // catch-all RuntimeException handler this propagated straight out of
            // evaluate(), aborting the caller (ActivityRuntime.evaluateGuard has no
            // try/catch of its own on the default, unsandboxed path).
            val result =
                ev.evaluate(guard = "x->includes()", instance = instance(mapOf("x" to listOf(1, 2))), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'x->forAll(1)' — an iterator call without a 'v | body' lambda — fails closed, never throws") {
            // forAll/exists dereference expr.body!! unconditionally; a call with a
            // plain argument instead of a "v | expr" binding leaves body null and
            // throws a NullPointerException instead of a declared exception type.
            val result =
                ev.evaluate(guard = "x->forAll(1)", instance = instance(mapOf("x" to listOf(1, 2))), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Regression: fail-open via a FunctionCall operand (MAJOR finding) ───

        test("a comparison against a function call is fail-closed (never True), regardless of variable state") {
            // ExpressionEvaluator.evaluate resolves every FunctionCall to `null`
            // unconditionally (function resolution is not implemented yet), so
            // without the referencesUnresolvedVariable fix, "foo() != 1" would
            // evaluate NEQ(null, 1) = true and be trusted as a definite branch
            // decision on every input, independent of any real state.
            for (guard in listOf("foo() != 1", "foo() == null", "getStatus() != 'rejected'")) {
                val result = ev.evaluate(guard = guard, instance = instance(), event = Event.of("advance"))
                withClue(guard) {
                    result.shouldNotBe(GuardResult.True)
                }
            }
        }

        test("'approved && audit() != 1' is fail-closed even though 'approved' alone would be True") {
            // Regression for the exact reported exploit shape: a short-circuit-safe
            // leading condition does not exempt a later FunctionCall operand from
            // the missing-variable check once it is actually visited.
            val result =
                ev.evaluate(
                    guard = "approved && audit() != 1",
                    instance = instance(mapOf("approved" to true)),
                    event = Event.of("advance"),
                )
            result.shouldNotBe(GuardResult.True)
        }

        // ── Regression: OCL '<>' fail-open against a missing variable ──────────

        test("'<>' against a missing variable is fail-closed (never True), matching '!=' on the AST path") {
            // dev.kuml.core.ocl.OclEvaluator's VarRef lookup is exactly as
            // null-tolerant as ExpressionEvaluator's AttributeRef: without
            // OclExpressions.evaluateTracked, "x <> 1" with a missing "x" would
            // evaluate `null != 1` = true on the legacy OCL front-end and be
            // trusted — the docs/handbook explicitly present '!=' and '<>' as
            // equivalent spellings of the same guard, so they must fail the same
            // direction.
            val result = ev.evaluate(guard = "missingVar <> 1", instance = instance(), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'<>' against a present variable is unaffected by the missing-variable fix") {
            ev.evaluate(guard = "x <> 1", instance = instance(mapOf("x" to 2)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "x <> 1", instance = instance(mapOf("x" to 1)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        // ── Regression: StackOverflowError not caught at the evaluate() boundary ─

        test("a huge '&&' chain does not throw StackOverflowError — fails closed instead") {
            // Unlike the '!'-chain test above (bounded by
            // OclLikeExpressionParser.MAX_NESTING_DEPTH during *parsing*), a long
            // "&&"-chain parses fine (parseAnd is iterative, not recursive) and
            // only overflows the stack during the recursive tree-walking
            // *evaluation* in ExpressionEvaluator.evalBinary — a case the parser's
            // own depth cap cannot see coming.
            val guard = (1..20_000).joinToString(separator = " && ") { "a" }
            val result = ev.evaluate(guard = guard, instance = instance(mapOf("a" to true)), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("a huge OCL 'and' chain does not throw StackOverflowError — fails closed instead") {
            // Same shape as above, but on the legacy dev.kuml.core.ocl front-end
            // (reached because "and" is not a C-like-dialect token).
            val guard = (1..20_000).joinToString(separator = " and ") { "a" }
            val result = ev.evaluate(guard = guard, instance = instance(mapOf("a" to true)), event = Event.of("advance"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Regression: short-circuit-blind missing-variable check (MINOR finding) ─

        test("'||' short-circuit: a True left operand does not require the untouched right operand to be present") {
            // isVip is definitely true, so ExpressionEvaluator never evaluates
            // "spendOver1000" at all — a static (non-short-circuit-aware) check of
            // the whole parsed tree would still flag it as missing and wrongly
            // downgrade this legitimate True to a GUARD_EVALUATION_FAILED warning.
            ev.evaluate(
                guard = "isVip || spendOver1000",
                instance = instance(mapOf("isVip" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'&&' short-circuit: a False left operand does not require the untouched right operand to be present") {
            ev.evaluate(
                guard = "isVip && spendOver1000",
                instance = instance(mapOf("isVip" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        test("'||' both operands visited: a missing right operand is still fail-closed when the left is False") {
            val result =
                ev.evaluate(
                    guard = "isVip || spendOver1000",
                    instance = instance(mapOf("isVip" to false)),
                    event = Event.of("advance"),
                )
            result.shouldNotBe(GuardResult.True)
        }

        test("'&&' both operands visited: a missing right operand is still fail-closed when the left is True") {
            val result =
                ev.evaluate(
                    guard = "isVip && spendOver1000",
                    instance = instance(mapOf("isVip" to true)),
                    event = Event.of("advance"),
                )
            result.shouldNotBe(GuardResult.True)
        }

        // ── Regression: OCL '<>' fail-open via dot-navigation ('vars.x'/'event.x') ─
        // (RUNDE-2-BEFUND #1: the missing-variable tracking above only covered the
        // bare-identifier VarRef path; 'vars.x <> 1' / 'event.x <> 1' navigate a Map
        // receiver and silently returned null for a missing key, bypassing the fix.)

        test("'vars.missing <> 1' is fail-closed (never True) when 'missing' is absent from variables") {
            val result = ev.evaluate(guard = "vars.missing <> 1", instance = instance(), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'event.missing <> 1' is fail-closed (never True) when 'missing' is absent from the event map") {
            val result = ev.evaluate(guard = "event.missing <> 1", instance = instance(), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.status <> \'rejected\'' is fail-closed when 'status' was never set") {
            // The BPMN exclusive-gateway scenario from the finding: a guard meant to
            // read "not rejected" must not silently pass every token when the event
            // stream never populates 'status' at all.
            val result = ev.evaluate(guard = "vars.status <> 'rejected'", instance = instance(), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'event.approved <> true' is fail-closed when 'approved' is absent, even with other keys present") {
            val result =
                ev.evaluate(
                    guard = "event.approved <> true",
                    instance = instance(mapOf("other" to 1)),
                    event = Event.of("advance"),
                )
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.missing <> 1 and allow' is fail-closed: the missing variable is on the side that mattered") {
            val result =
                ev.evaluate(
                    guard = "vars.missing <> 1 and allow",
                    instance = instance(mapOf("allow" to true)),
                    event = Event.of("advance"),
                )
            result.shouldNotBe(GuardResult.True)
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.x <> 1' is unaffected by the dot-navigation fix when 'x' is present") {
            ev.evaluate(guard = "vars.x <> 1", instance = instance(mapOf("x" to 2)), event = Event.of("advance")) shouldBe
                GuardResult.True
            ev.evaluate(guard = "vars.x <> 1", instance = instance(mapOf("x" to 1)), event = Event.of("advance")) shouldBe
                GuardResult.False
        }

        // ── Regression: OCL 'or'/'implies' eagerness tainted a legitimate True ──
        // (RUNDE-2-BEFUND #2: unlike the AST '||'/'&&' path above, the OCL front-end
        // evaluated both operands unconditionally, so a missing variable on a branch
        // that never determined the result still downgraded a true 'or'/'implies'
        // guard to Failed.)

        test("OCL 'or' short-circuit: a True left operand does not require the untouched right operand to be present") {
            ev.evaluate(
                guard = "urgent or vip",
                instance = instance(mapOf("urgent" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("OCL 'implies' short-circuit: a False left operand does not require the untouched right operand to be present") {
            ev.evaluate(
                guard = "blocked implies override",
                instance = instance(mapOf("blocked" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("OCL 'not x or y' short-circuit variant from the finding: 'not blocked or override'") {
            ev.evaluate(
                guard = "not blocked or override",
                instance = instance(mapOf("blocked" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("OCL 'or' with both variables present is unaffected by the short-circuit fix") {
            ev.evaluate(
                guard = "urgent or vip",
                instance = instance(mapOf("urgent" to true, "vip" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("OCL 'or' both operands visited: a missing right operand is still fail-closed when the left is False") {
            val result =
                ev.evaluate(guard = "urgent or vip", instance = instance(mapOf("urgent" to false)), event = Event.of("advance"))
            result.shouldNotBe(GuardResult.True)
        }

        // ── Regression: 'implies' with a present-but-non-Boolean left operand ────
        // used to be treated as concretely `false` (the "false implies invalid =
        // true" shortcut), skipping the right operand entirely and returning an
        // untainted `True` — a naturally-occurring case whenever an
        // approval-style guard's left operand arrives as a String/Int/`null`
        // instead of a JSON Boolean (e.g. `"true"`/`1` from a loosely-typed event
        // payload), which fires the edge unconditionally without ever consulting
        // the right operand.

        test("'vars.flag implies vars.approved' with a String flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "vars.flag implies vars.approved",
                    instance = instance(mapOf("flag" to "true")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.flag implies vars.approved' with an Int flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "vars.flag implies vars.approved",
                    instance = instance(mapOf("flag" to 1)),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.flag implies vars.approved' with a present-but-null flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "vars.flag implies vars.approved",
                    instance = instance(mapOf("flag" to null)),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.flag implies vars.approved' with a real Boolean true flag and a missing approved is False, not True") {
            ev.evaluate(
                guard = "vars.flag implies vars.approved",
                instance = instance(mapOf("flag" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        test("'vars.flag implies vars.approved' with a real Boolean true flag and approved=true still fires") {
            ev.evaluate(
                guard = "vars.flag implies vars.approved",
                instance = instance(mapOf("flag" to true, "approved" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("a nested 'implies' with a non-Boolean inner left operand still fails closed through an outer trustworthy True") {
            val result =
                ev.evaluate(
                    guard = "vars.flag implies (vars.other implies vars.approved)",
                    instance = instance(mapOf("flag" to true, "other" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Regression: 'and' with a present-but-non-Boolean left operand used ───
        // to short-circuit to a concrete, untainted `false` without ever
        // consulting the right operand (the same defect just fixed for
        // 'implies' above, present in 'and' too). Because that `false` was
        // untainted, negating it or comparing it to `false` escalated the guard
        // to an untainted `True` even though `vars.approved` — the operand that
        // was supposed to gate the edge — was never looked up at all.

        test("'vars.flag and vars.approved' with a String flag and a missing approved is False, not tainted-through-negation") {
            ev.evaluate(
                guard = "vars.flag and vars.approved",
                instance = instance(mapOf("flag" to "yes")),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        test("'not (vars.flag and vars.approved)' with a String flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.flag and vars.approved)",
                    instance = instance(mapOf("flag" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.flag and vars.approved)' with an Int flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.flag and vars.approved)",
                    instance = instance(mapOf("flag" to 1)),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.flag and vars.approved)' with a present-but-null flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.flag and vars.approved)",
                    instance = instance(mapOf("flag" to null)),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'(vars.flag and vars.approved) implies false' with a String flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "(vars.flag and vars.approved) implies false",
                    instance = instance(mapOf("flag" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'(vars.flag and vars.approved) = false' with a String flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "(vars.flag and vars.approved) = false",
                    instance = instance(mapOf("flag" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'(vars.flag and vars.approved) <> true' with a String flag and a missing approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "(vars.flag and vars.approved) <> true",
                    instance = instance(mapOf("flag" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'if (vars.flag and vars.approved) then false else true endif' with a String flag fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "if (vars.flag and vars.approved) then false else true endif",
                    instance = instance(mapOf("flag" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.flag and vars.approved)' with a real Boolean true flag and a missing approved still fails closed") {
            val result =
                ev.evaluate(
                    guard = "not (vars.flag and vars.approved)",
                    instance = instance(mapOf("flag" to true)),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.flag and vars.approved)' with flag=false correctly fires (OCL 'false and X = false')") {
            ev.evaluate(
                guard = "not (vars.flag and vars.approved)",
                instance = instance(mapOf("flag" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'not (vars.flag and vars.approved)' with flag=true and approved=false correctly fires") {
            ev.evaluate(
                guard = "not (vars.flag and vars.approved)",
                instance = instance(mapOf("flag" to true, "approved" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'vars.approvalRequired and vars.approved' with a String approvalRequired and a missing approved is False") {
            ev.evaluate(
                guard = "vars.approvalRequired and vars.approved",
                instance = instance(mapOf("approvalRequired" to "true")),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        test("'not (vars.approvalRequired and vars.approved)' with a String approvalRequired fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.approvalRequired and vars.approved)",
                    instance = instance(mapOf("approvalRequired" to "true")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.flag and vars.approved' with a non-Boolean flag still fires False-untainted when approved is concretely false") {
            ev.evaluate(
                guard = "vars.flag and vars.approved",
                instance = instance(mapOf("flag" to "yes", "approved" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        test("'vars.flag and vars.approved' with both operands real Booleans true still fires True") {
            ev.evaluate(
                guard = "vars.flag and vars.approved",
                instance = instance(mapOf("flag" to true, "approved" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        // ══ Systematic sweep of the remaining silent-narrowing fail-open paths ══
        //    in dev.kuml.core.ocl.OclEvaluator, exercised end-to-end through the
        //    OCL front-end of this evaluator. Each of these guards previously
        //    returned GuardResult.True — i.e. fired the edge — even though the
        //    result was really built on data that was missing or arrived with the
        //    wrong type. OclEvaluatorTest covers the taint flag itself; these
        //    tests pin the observable guard outcome.

        test("'not ((vars.missing <> 1) implies vars.b)' fails closed instead of firing (implies dropped its left taint)") {
            val result =
                ev.evaluate(
                    guard = "not ((vars.missing <> 1) implies vars.b)",
                    instance = instance(mapOf("b" to false)),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.items->forAll(i | i.flag))' with a String flag fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.items->forAll(i | i.flag))",
                    instance = instance(mapOf("items" to listOf(mapOf("flag" to "yes")))),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.items->reject(i | i.flag)->notEmpty()' with a String flag fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "vars.items->reject(i | i.flag)->notEmpty()",
                    instance = instance(mapOf("items" to listOf(mapOf("flag" to "yes")))),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.name->forAll(i | i.flag)' over a non-collection receiver fails closed instead of firing vacuously") {
            val result =
                ev.evaluate(
                    guard = "vars.name->forAll(i | i.flag)",
                    instance = instance(mapOf("name" to "Alice")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.name->isEmpty()' over a non-collection receiver fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "vars.name->isEmpty()",
                    instance = instance(mapOf("name" to "Alice")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.name->iterate(i; acc = true | acc and i.flag)' over a non-collection receiver fails closed") {
            val result =
                ev.evaluate(
                    guard = "vars.name->iterate(i; acc = true | acc and i.flag)",
                    instance = instance(mapOf("name" to "Alice")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Happy paths that must NOT become over-strict ─────────────────────────

        test("'vars.items->forAll(i | i.flag)' with real Boolean flags still fires True") {
            ev.evaluate(
                guard = "vars.items->forAll(i | i.flag)",
                instance = instance(mapOf("items" to listOf(mapOf("flag" to true), mapOf("flag" to true)))),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'vars.items->exists(i | i.flag)' short-circuits on a real true and still fires True despite a later gap") {
            ev.evaluate(
                guard = "vars.items->exists(i | i.flag)",
                instance = instance(mapOf("items" to listOf(mapOf("flag" to true), mapOf<String, Any?>()))),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'vars.items->notEmpty()' over a real collection still fires True") {
            ev.evaluate(
                guard = "vars.items->notEmpty()",
                instance = instance(mapOf("items" to listOf(1, 2))),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'(vars.missing <> 1) implies vars.b' with b=true still fires True ('X implies true' is true for any X)") {
            ev.evaluate(
                guard = "(vars.missing <> 1) implies vars.b",
                instance = instance(mapOf("b" to true)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'vars.a implies vars.b' with both real Booleans (true implies false) is a plain untainted False") {
            ev.evaluate(
                guard = "vars.a implies vars.b",
                instance = instance(mapOf("a" to true, "b" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }

        // ══ Mirrored silent-narrowing gap: the RIGHT operand of 'and'/'implies' ══
        //    and the "unknown ⇒ false" arms of 'or' (branch
        //    fix/activity-guard-negation, follow-up to the sweep above), pinned
        //    at the guard-outcome level. Each of these guards previously
        //    returned GuardResult.True — firing the edge — even though the
        //    result genuinely depended on an operand that was present but
        //    wrongly typed.

        test("'not (vars.flag and vars.approved)' with flag=true and a String approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.flag and vars.approved)",
                    instance = instance(mapOf("flag" to true, "approved" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.flag or vars.approved)' with flag=false and a String approved fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.flag or vars.approved)",
                    instance = instance(mapOf("flag" to false, "approved" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'not (vars.a implies vars.b)' with a=true and a String b fails closed instead of firing") {
            val result =
                ev.evaluate(
                    guard = "not (vars.a implies vars.b)",
                    instance = instance(mapOf("a" to true, "b" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'(vars.flag and vars.approved) = false' with flag=true and a String approved fails closed") {
            val result =
                ev.evaluate(
                    guard = "(vars.flag and vars.approved) = false",
                    instance = instance(mapOf("flag" to true, "approved" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'if (vars.flag and vars.approved) then false else true endif' with flag=true and a String approved fails closed") {
            val result =
                ev.evaluate(
                    guard = "if (vars.flag and vars.approved) then false else true endif",
                    instance = instance(mapOf("flag" to true, "approved" to "yes")),
                    event = Event.of("advance"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // ── Controls: must NOT become over-strict ───────────────────────────────

        test("'not (vars.flag and vars.approved)' with flag=true and approved=false still fires True") {
            ev.evaluate(
                guard = "not (vars.flag and vars.approved)",
                instance = instance(mapOf("flag" to true, "approved" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.True
        }

        test("'vars.flag or vars.approved' with both real Booleans false is a plain untainted False") {
            ev.evaluate(
                guard = "vars.flag or vars.approved",
                instance = instance(mapOf("flag" to false, "approved" to false)),
                event = Event.of("advance"),
            ) shouldBe GuardResult.False
        }
    })
