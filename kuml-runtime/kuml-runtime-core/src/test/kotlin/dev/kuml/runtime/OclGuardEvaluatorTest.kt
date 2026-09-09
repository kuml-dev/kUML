package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class OclGuardEvaluatorTest :
    FunSpec({

        fun newInstance(): StateMachineInstance {
            // Minimal instance for guard evaluation — no transitions are fired here.
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A")),
                    transitions = listOf(trans(id = "t0", from = "init", to = "A")),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            return rt.start(sm)
        }

        val ev = OclGuardEvaluator()

        test("null guard returns True") {
            ev.evaluate(guard = null, instance = newInstance(), event = Event.of("any")) shouldBe GuardResult.True
        }

        test("blank guard returns True") {
            ev.evaluate(guard = "   ", instance = newInstance(), event = Event.of("any")) shouldBe GuardResult.True
        }

        test("square brackets are stripped before parsing") {
            ev.evaluate(guard = "[true]", instance = newInstance(), event = Event.of("any")) shouldBe GuardResult.True
            ev.evaluate(guard = "[false]", instance = newInstance(), event = Event.of("any")) shouldBe GuardResult.False
        }

        test("bare boolean literal true evaluates") {
            ev.evaluate(guard = "true", instance = newInstance(), event = Event.of("any")) shouldBe GuardResult.True
        }

        test("event.amount > 100 evaluates true for payload 150") {
            val event =
                Event(
                    name = "pay",
                    payload =
                        buildJsonObject {
                            put("amount", JsonPrimitive(150))
                        },
                )
            ev.evaluate(guard = "event.amount > 100", instance = newInstance(), event = event) shouldBe GuardResult.True
        }

        test("event.amount > 100 evaluates false for payload 50") {
            val event =
                Event(
                    name = "pay",
                    payload =
                        buildJsonObject {
                            put("amount", JsonPrimitive(50))
                        },
                )
            ev.evaluate(guard = "event.amount > 100", instance = newInstance(), event = event) shouldBe GuardResult.False
        }

        test("vars.flag boolean access returns instance variable value") {
            val instance = newInstance()
            instance.variables["flag"] = true
            ev.evaluate(guard = "vars.flag", instance = instance, event = Event.of("any")) shouldBe GuardResult.True

            instance.variables["flag"] = false
            ev.evaluate(guard = "vars.flag", instance = instance, event = Event.of("any")) shouldBe GuardResult.False
        }

        test("parse error returns Failed with clear message") {
            val result = ev.evaluate(guard = "!!! invalid !!!", instance = newInstance(), event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("eval error returns Failed (unknown navigation)") {
            val result = ev.evaluate(guard = "event.amount.weird.path", instance = newInstance(), event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("non-boolean result returns Failed") {
            val result = ev.evaluate(guard = "42", instance = newInstance(), event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("missing payload key returns Failed (navigation on null)") {
            val event = Event(name = "pay", payload = JsonObject(emptyMap()))
            // event.amount → null (Map lookup) → OCL Navigate on null → exception
            // Or if amount exists but the further navigation is null. Either way Failed.
            val result = ev.evaluate(guard = "event.amount > 100", instance = newInstance(), event = event)
            // Could be Failed or False depending on null-handling — we just require non-true.
            (result == GuardResult.False || result is GuardResult.Failed) shouldBe true
        }

        // ── Regression: fail-open via '<>'/'!=' against a missing variable ────
        // (fix/stm-guard-fail-open — the reported bug and its broader AST-path
        // sibling. Both OclGuardEvaluator's internal paths, "legacy" and "AST",
        // previously trusted a `true` comparison built on data that was never
        // provided.)

        test("'vars.missing <> 1' is fail-closed (never True) when 'missing' is absent from variables") {
            val result = ev.evaluate(guard = "vars.missing <> 1", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'event.missing <> 1' is fail-closed (never True) when 'missing' is absent from the event payload") {
            val event = Event(name = "pay", payload = JsonObject(emptyMap()))
            val result = ev.evaluate(guard = "event.missing <> 1", instance = newInstance(), event = event)
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.status <> rejected' (string literal) is fail-closed when 'status' was never set") {
            val result = ev.evaluate(guard = "vars.status <> 'rejected'", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'event.approved <> true' is fail-closed when 'approved' is absent, even with other payload keys present") {
            val event =
                Event(
                    name = "check",
                    payload = buildJsonObject { put("other", JsonPrimitive(1)) },
                )
            val result = ev.evaluate(guard = "event.approved <> true", instance = newInstance(), event = event)
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.missing <> 1 and vars.allow' is fail-closed: the missing variable is on the side that mattered") {
            val instance = newInstance()
            instance.variables["allow"] = true
            val result = ev.evaluate(guard = "vars.missing <> 1 and vars.allow", instance = instance, event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.missing != 1' (AST dialect) is fail-closed (never True)") {
            val result = ev.evaluate(guard = "vars.missing != 1", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'event.missing != 1' (AST dialect) is fail-closed (never True)") {
            val event = Event(name = "pay", payload = JsonObject(emptyMap()))
            val result = ev.evaluate(guard = "event.missing != 1", instance = newInstance(), event = event)
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        // A bare identifier (no `vars.`/`event.` prefix) is not excluded from the
        // fix by design — GuardEvaluatorFailOpenParityTest deliberately leaves it
        // out only because `vars`/`event` bind differently between this STM
        // evaluator and ActivityGuardEvaluator (see that test's class doc), not
        // because this evaluator itself special-cases bare identifiers. Both
        // OclGuardEvaluator's internal paths resolve a bare identifier the same
        // null-tolerant way a `vars.x` dot-navigation does — the legacy OCL
        // front-end's `VarRef` looks the name up directly in `env`, and the AST
        // path's `AttributeRef` looks the first path segment up directly in
        // `context` — so a missing bare identifier must fail closed exactly like
        // ActivityGuardEvaluatorTest's "'missingVar <> 1' ... is fail-closed"
        // pins on the Activity/BPMN path.

        test("'missing <> 1' (bare identifier) is fail-closed (never True)") {
            val result = ev.evaluate(guard = "missing <> 1", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'missing != 1' (bare identifier, AST dialect) is fail-closed (never True)") {
            val result = ev.evaluate(guard = "missing != 1", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.a == vars.b' with both missing is not True") {
            val result = ev.evaluate(guard = "vars.a == vars.b", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
        }

        test("a comparison against a function call is fail-closed (never True), regardless of variable state") {
            val result = ev.evaluate(guard = "audit() != 1", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
        }

        test("'vars.flag && audit() != 1' is fail-closed even though 'vars.flag' alone would be True") {
            val instance = newInstance()
            instance.variables["flag"] = true
            val result = ev.evaluate(guard = "vars.flag && audit() != 1", instance = instance, event = Event.of("any"))
            result shouldNotBe GuardResult.True
        }

        // ── Negative controls: must NOT become over-strict ─────────────────────

        test("'vars.x <> 1' is unaffected by the missing-variable fix when 'x' is present") {
            val instance = newInstance()
            instance.variables["x"] = 2
            ev.evaluate(guard = "vars.x <> 1", instance = instance, event = Event.of("any")) shouldBe GuardResult.True

            instance.variables["x"] = 1
            ev.evaluate(guard = "vars.x <> 1", instance = instance, event = Event.of("any")) shouldBe GuardResult.False
        }

        test("'vars.x != 1' is unaffected by the missing-variable fix when 'x' is present") {
            val instance = newInstance()
            instance.variables["x"] = 2
            ev.evaluate(guard = "vars.x != 1", instance = instance, event = Event.of("any")) shouldBe GuardResult.True
        }

        // ── Null-comparison presence-check idiom: '<> null'/'!= null' contract ──
        // (Runde-2-Review-Befund: the CHANGELOG/ocl.adoc "is X set?" idiom claim —
        // missing → False, present-and-non-null → True, present-and-null → False —
        // had no pinning test at all. Covers all three states for both dialects.)

        test("'vars.x <> null' presence-check idiom: missing, non-null, and null states") {
            val instance = newInstance()
            // Missing entirely: never True, and specifically False (not Failed) —
            // this is the documented "unaffected by the fail-closed rule" case.
            ev.evaluate(guard = "vars.x <> null", instance = instance, event = Event.of("any")) shouldBe GuardResult.False

            instance.variables["x"] = 5
            ev.evaluate(guard = "vars.x <> null", instance = instance, event = Event.of("any")) shouldBe GuardResult.True

            instance.variables["x"] = null
            ev.evaluate(guard = "vars.x <> null", instance = instance, event = Event.of("any")) shouldBe GuardResult.False
        }

        test("'vars.x != null' presence-check idiom: missing, non-null, and null states") {
            val instance = newInstance()
            ev.evaluate(guard = "vars.x != null", instance = instance, event = Event.of("any")) shouldBe GuardResult.False

            instance.variables["x"] = 5
            ev.evaluate(guard = "vars.x != null", instance = instance, event = Event.of("any")) shouldBe GuardResult.True

            instance.variables["x"] = null
            ev.evaluate(guard = "vars.x != null", instance = instance, event = Event.of("any")) shouldBe GuardResult.False
        }

        // ── Mirror-image idiom: '= null'/'== null' ("is X *not* set?") IS a ──────
        // breaking change (Runde-2-Review-Befund 1). Unlike '<> null'/'!= null'
        // above, a missing 'x' makes this comparison evaluate to a true that is
        // built on data that was never provided — exactly the shape this fix
        // downgrades. Before this fix (see master), this was GuardResult.True;
        // it must now be GuardResult.Failed. This pins the documented breaking
        // change so a future change to the taint check cannot silently revert it
        // without a test going red — see CHANGELOG.md and ocl.adoc.

        test("'vars.x = null' with missing 'x' is Failed, not True (breaking change vs. pre-fix behavior)") {
            val result = ev.evaluate(guard = "vars.x = null", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.x == null' with missing 'x' is Failed, not True (breaking change vs. pre-fix behavior)") {
            val result = ev.evaluate(guard = "vars.x == null", instance = newInstance(), event = Event.of("any"))
            result shouldNotBe GuardResult.True
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.x == null' evaluates True when 'x' is present and genuinely null") {
            val instance = newInstance()
            instance.variables["x"] = null
            ev.evaluate(guard = "vars.x == null", instance = instance, event = Event.of("any")) shouldBe GuardResult.True
        }

        test("'vars.isVip || vars.spendOver1000' short-circuit: True left operand does not require the untouched right to be present") {
            val instance = newInstance()
            instance.variables["isVip"] = true
            ev.evaluate(guard = "vars.isVip || vars.spendOver1000", instance = instance, event = Event.of("any")) shouldBe
                GuardResult.True
        }

        test("'vars.blocked && vars.override' with blocked=false is False, not Failed (AND short-circuit)") {
            val instance = newInstance()
            instance.variables["blocked"] = false
            ev.evaluate(guard = "vars.blocked && vars.override", instance = instance, event = Event.of("any")) shouldBe
                GuardResult.False
        }

        test("OCL 'or' short-circuit: True left operand does not require the untouched right operand to be present") {
            val instance = newInstance()
            instance.variables["urgent"] = true
            ev.evaluate(guard = "vars.urgent or vars.vip", instance = instance, event = Event.of("any")) shouldBe
                GuardResult.True
            // Operand order must not matter.
            ev.evaluate(guard = "vars.vip or vars.urgent", instance = instance, event = Event.of("any")) shouldBe
                GuardResult.True
        }

        test("OCL 'implies' short-circuit: False left operand does not require the untouched right operand to be present") {
            val instance = newInstance()
            instance.variables["a"] = false
            ev.evaluate(guard = "vars.a implies vars.b", instance = instance, event = Event.of("any")) shouldBe
                GuardResult.True
        }

        test("'vars.missing.oclIsUndefined()' is True, not Failed (documented presence-check idiom)") {
            ev.evaluate(guard = "vars.missing.oclIsUndefined()", instance = newInstance(), event = Event.of("any")) shouldBe
                GuardResult.True
        }

        test(
            "'vars.x.oclIsUndefined()' is also True for a present-but-null variable " +
                "(cannot distinguish 'missing' from 'set to null', per docs/handbook ocl.adoc)",
        ) {
            val instance = newInstance()
            instance.variables["cancelReason"] = null
            ev.evaluate(guard = "vars.cancelReason.oclIsUndefined()", instance = instance, event = Event.of("any")) shouldBe
                GuardResult.True
        }

        test("'vars.a || vars.missing' with a=false is fail-closed: the right side was visited and is unknown") {
            val instance = newInstance()
            instance.variables["a"] = false
            val result = ev.evaluate(guard = "vars.a || vars.missing", instance = instance, event = Event.of("any"))
            result shouldNotBe GuardResult.True
        }

        test("'vars.a && vars.missing' with a=true is fail-closed: the right side was visited and is unknown") {
            val instance = newInstance()
            instance.variables["a"] = true
            val result = ev.evaluate(guard = "vars.a && vars.missing", instance = instance, event = Event.of("any"))
            result shouldNotBe GuardResult.True
        }

        // ── "evaluate never throws" contract tests ─────────────────────────────

        test("'vars.items->includes()' fails closed, never throws (NoSuchElementException path)") {
            val instance = newInstance()
            instance.variables["items"] = listOf(1, 2)
            val result = ev.evaluate(guard = "vars.items->includes()", instance = instance, event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("'vars.items->forAll(1)' fails closed, never throws (NullPointerException path)") {
            val instance = newInstance()
            instance.variables["items"] = listOf(1, 2)
            val result = ev.evaluate(guard = "vars.items->forAll(1)", instance = instance, event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("a huge '&&' chain fails closed instead of throwing StackOverflowError") {
            val instance = newInstance()
            instance.variables["a"] = true
            val guard = (1..20_000).joinToString(separator = " && ") { "vars.a" }
            val result = ev.evaluate(guard = guard, instance = instance, event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("a huge OCL 'and' chain fails closed instead of throwing StackOverflowError") {
            val instance = newInstance()
            instance.variables["a"] = true
            val guard = (1..20_000).joinToString(separator = " and ") { "vars.a" }
            val result = ev.evaluate(guard = guard, instance = instance, event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("a long '&&' chain's evaluation time grows linearly with length, not quadratically") {
            // Regression guard for the GuardAstTaint O(n^2) finding on
            // fix/stm-guard-fail-open: the taint check that runs after a
            // successful `true` evaluation used to re-evaluate the growing
            // left subtree of a left-associative "&&" chain at every level
            // (OclLikeExpressionParser.parseAnd builds this shape via an
            // iterative loop, so MAX_NESTING_DEPTH — which only bounds
            // recursive '!'/'-'/'(' nesting — never rejects it), turning an
            // O(n) walk into O(n^2). Rather than assert an absolute
            // millisecond ceiling (flaky across CI hardware, and hard to
            // calibrate against a JIT-warmed few-millisecond baseline), this
            // compares the time for a 4x-longer chain against a shorter one
            // on the *same* run: doubling n twice should roughly quadruple
            // the time for a true O(n) walk, but multiply it ~16x for a
            // reintroduced O(n^2) — an 8x ratio ceiling comfortably separates
            // the two without chasing an absolute-time budget.
            //
            // n=1,200 is chosen (rather than the n=20,000 the
            // StackOverflowError tests above use) specifically to stay well
            // clear of this evaluator's recursive-descent stack limit on a
            // Gradle test-worker JVM's default thread stack size (empirically
            // ~1,200-1,600 terms on this codebase's CI/dev machines) — this
            // test needs both chains to actually *finish*, not overflow.
            val instance = newInstance()
            instance.variables["a"] = true
            val event = Event.of("any")

            fun timedRunsMs(n: Int): Long {
                val guard = (1..n).joinToString(separator = " && ") { "vars.a" }
                // Warm up the parse cache and JIT before timing.
                ev.evaluate(guard = guard, instance = instance, event = event) shouldBe GuardResult.True
                return (1..5)
                    .map {
                        val start = System.nanoTime()
                        ev.evaluate(guard = guard, instance = instance, event = event) shouldBe GuardResult.True
                        System.nanoTime() - start
                    }.min() / 1_000_000
            }

            val shortMs = timedRunsMs(n = 300).coerceAtLeast(1L)
            val longMs = timedRunsMs(n = 1_200)

            // O(n): ~4x. O(n^2): ~16x. 8x leaves headroom for scheduling
            // noise on either measurement without masking a real regression.
            (longMs.toDouble() / shortMs.toDouble() < 8.0) shouldBe true
        }

        test("a fail-open comparison against a missing variable, buried partway through a 1,200-term '&&' chain, still fails closed") {
            // Covers the same previously-untested "expensive band" as the
            // timing test above, but for correctness rather than
            // performance: "vars.missing != 1" alone evaluates to a definite
            // (fail-open) `true` — see this class's "'vars.a && vars.missing'
            // with a=true is fail-closed" test and GuardAstTaint's KDoc — so
            // burying it partway through a long chain (not just at the very
            // end) must still be caught by the short-circuit-aware taint
            // walk, not just skipped over because the walk now visits each
            // chain node only once.
            val instance = newInstance()
            instance.variables["a"] = true
            val terms = (1..1_200).map { i -> if (i == 600) "vars.missing != 1" else "vars.a" }
            val guard = terms.joinToString(separator = " && ")
            val result = ev.evaluate(guard = guard, instance = instance, event = Event.of("any"))
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }

        test("evaluate never throws for a table of malicious/malformed guard strings") {
            // Note: "" and "   " are deliberately excluded — a blank guard is
            // defined to be True (UML 2.5 §15.3.13, see the "blank guard
            // returns True" test above), so it must NOT be asserted non-True
            // here; this table only covers malformed/malicious *non-blank*
            // input.
            val guards =
                listOf(
                    "!!! invalid !!!",
                    "(".repeat(5000),
                    "!=",
                    "&&",
                    "a b c",
                    "vars.items->includes()",
                    "vars.items->forAll(1)",
                    "vars.missing <> 1",
                    "vars.missing != 1",
                    "audit() != 1",
                )
            for (guard in guards) {
                val result = ev.evaluate(guard = guard, instance = newInstance(), event = Event.of("any"))
                result shouldNotBe GuardResult.True
            }
        }

        test("taint from a missing left operand survives an exception thrown while evaluating the right operand") {
            val instance = newInstance()
            instance.variables["items"] = listOf(1, 2)
            val result =
                ev.evaluate(
                    guard = "vars.missing <> 1 and vars.items->closure(i | i.brokenNav)->isEmpty()",
                    instance = instance,
                    event = Event.of("any"),
                )
            result.shouldBeInstanceOf<GuardResult.Failed>()
        }
    })
