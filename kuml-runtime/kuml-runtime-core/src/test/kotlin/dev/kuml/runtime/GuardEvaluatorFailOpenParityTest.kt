package dev.kuml.runtime

import dev.kuml.runtime.activity.ActivityGuardEvaluator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Structural parity guard between [OclGuardEvaluator] (STM guards) and
 * [dev.kuml.runtime.activity.ActivityGuardEvaluator] (Activity/BPMN
 * token-flow guards) — fix/stm-guard-fail-open.
 *
 * Both evaluators share exactly one implementation of the AST-side
 * missing-variable taint check
 * ([dev.kuml.runtime.internal.GuardAstTaint]), but each still has its own
 * `evaluate()`/legacy-OCL wiring. Before this fix, only the Activity path had
 * been hardened against a `true` comparison built on a missing variable — the
 * STM path (this evaluator's actual subject) silently trusted the same
 * fail-open result. This test pins a table of guard strings that must be
 * answered the *same way* — "definitely not True" or "definitely True" — by
 * both evaluators, so a future change that hardens only one side is caught
 * here instead of shipping unnoticed, the way this bug did.
 *
 * Only `vars.`-prefixed dot-navigation and bare function calls are used
 * (never a bare identifier, never `event.`): [OclGuardEvaluator]'s `env` binds
 * `vars` to [ModelInstance.variables] and `event` to a *separate* eval-map
 * built from the [Event] payload, whereas [ActivityGuardEvaluator]'s `env`
 * binds both `vars` and `event` (and the flat top level) to the *same*
 * `instance.variables` map — so a bare identifier or an `event.`-prefixed
 * guard would not necessarily see the same data on both evaluators. `vars.`
 * always resolves against `instance.variables` on both, which is what this
 * test keeps identical across the two evaluator instances.
 */
class GuardEvaluatorFailOpenParityTest :
    FunSpec({

        fun stmInstance(vars: Map<String, Any?> = emptyMap()): StateMachineInstance {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A")),
                    transitions = listOf(trans(id = "t0", from = "init", to = "A")),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            vars.forEach { (k, v) -> instance.variables[k] = v }
            return instance
        }

        class ActivityTestInstance(
            variables: Map<String, Any?>,
        ) : ModelInstance<Any> {
            override val model: Any get() = error("not used")
            override val currentVertices: List<dev.kuml.uml.UmlVertex> = emptyList()
            override val variables: MutableMap<String, Any?> = variables.toMutableMap()
            override val isTerminated: Boolean = false
            override val trace: List<TraceEntry> = emptyList()
        }

        val stmEvaluator = OclGuardEvaluator()
        val activityEvaluator = ActivityGuardEvaluator()

        // Guards that must be "definitely not True" on both evaluators when the
        // referenced variable(s) are missing/never provided.
        val failOpenGuards =
            listOf(
                "vars.missing <> 1",
                "vars.missing != 1",
                "vars.status <> 'rejected'",
                "audit() != 1",
            )

        for (guard in failOpenGuards) {
            test("'$guard' with missing data is not True on either evaluator") {
                val stmResult = stmEvaluator.evaluate(guard = guard, instance = stmInstance(), event = Event.of("any"))
                val activityResult =
                    activityEvaluator.evaluate(guard = guard, instance = ActivityTestInstance(emptyMap()), event = Event.of("any"))

                stmResult shouldNotBe GuardResult.True
                activityResult shouldNotBe GuardResult.True
            }
        }

        // Positive counterparts: once the referenced data is present, both
        // evaluators must still fire — the fix must not become over-strict.
        val presentVars = mapOf("x" to 2)
        val presentGuards = listOf("vars.x <> 1", "vars.x != 1")

        for (guard in presentGuards) {
            test("'$guard' with 'x' present (=2) is True on both evaluators") {
                val stmResult = stmEvaluator.evaluate(guard = guard, instance = stmInstance(presentVars), event = Event.of("any"))
                val activityResult =
                    activityEvaluator.evaluate(
                        guard = guard,
                        instance = ActivityTestInstance(presentVars),
                        event = Event.of("any"),
                    )

                stmResult shouldBe GuardResult.True
                activityResult shouldBe GuardResult.True
            }
        }

        test("'vars.isVip || vars.spendOver1000' short-circuit True is preserved on both evaluators") {
            val vars = mapOf("isVip" to true)
            val guard = "vars.isVip || vars.spendOver1000"

            stmEvaluator.evaluate(guard = guard, instance = stmInstance(vars), event = Event.of("any")) shouldBe
                GuardResult.True
            activityEvaluator.evaluate(guard = guard, instance = ActivityTestInstance(vars), event = Event.of("any")) shouldBe
                GuardResult.True
        }
    })
