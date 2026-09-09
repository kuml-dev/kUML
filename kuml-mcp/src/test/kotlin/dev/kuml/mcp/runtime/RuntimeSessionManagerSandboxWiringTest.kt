package dev.kuml.mcp.runtime

import dev.kuml.runtime.OclGuardEvaluator
import dev.kuml.runtime.activity.ActivityGuardEvaluator
import dev.kuml.runtime.sandbox.SandboxPolicy
import dev.kuml.runtime.sandbox.TimeLimitedGuardEvaluator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Structural regression guard for the ADR-0015 / security-fix-B2 sandbox
 * wiring in [RuntimeSessionManager]'s `buildStmSession`/`buildActSession`:
 * both MCP session paths must construct their runtime with guard evaluation
 * wrapped in [TimeLimitedGuardEvaluator], never the bare [OclGuardEvaluator]/
 * [ActivityGuardEvaluator] directly.
 *
 * Before this test, that wiring was a bare constructor argument with no
 * assertion anywhere in the suite (`grep -rl "TimeLimitedGuardEvaluator|
 * SandboxPolicy" kuml-mcp/src/test/` found nothing). A later refactor — e.g.
 * extracting a shared `buildSession` helper, or reverting either builder to
 * `StateMachineRuntime()`/`ActivityRuntime()`'s unsandboxed default
 * constructor — could silently drop the wrapper while every other test in
 * this module stayed green, re-opening exactly the unsandboxed-STM-guard gap
 * this branch (`fix/stm-guard-fail-open`) exists to close, on a path the ACT
 * side has been protected on since ADR-0015/B2.
 *
 * [dev.kuml.runtime.StateMachineRuntime]'s `guards` field and
 * [dev.kuml.runtime.activity.ActivityRuntime]'s `guardEvaluator` field are
 * (rightly) `private` — encapsulating the evaluator is the right default, so
 * this test reaches them via reflection rather than widening that
 * visibility. The point of this test is to catch a *silent* change to the
 * wiring, not to make the wiring easy to reach in production code too.
 */
class RuntimeSessionManagerSandboxWiringTest :
    FunSpec({

        val stmScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("Wiring") {
                val initial = stateDef("Initial", isInitial = true)
                val a = stateDef("A")
                transition("init", initial, a)
                stmDiagram("Wiring STM") {
                    include(initial)
                    include(a)
                }
            }
            """.trimIndent()

        val actScript =
            """
            import dev.kuml.sysml2.dsl.sysml2Model

            sysml2Model("WiringAct") {
                val init = initialNode()
                val step = actionDef("Step", action = "sensors.readAll()")
                val fin = finalNode()
                controlFlow("toStep", init, step)
                controlFlow("toFinal", step, fin)
                actDiagram("Wiring ACT") {
                    include(init)
                    include(step)
                    include(fin)
                }
            }
            """.trimIndent()

        /** Reads a `private val` field via reflection — see class KDoc for why. */
        fun <T> privateField(
            target: Any,
            name: String,
        ): T {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return field.get(target) as T
        }

        test("STM sessions sandbox guard evaluation behind TimeLimitedGuardEvaluator(OclGuardEvaluator, SandboxPolicy)") {
            val manager = RuntimeSessionManager()
            val started = manager.start(source = stmScript, kind = "stm", elementName = null)
            started.shouldBeInstanceOf<SessionResult.Started>()

            val session = manager.getSession(started.sessionId)
            session.shouldBeInstanceOf<RuntimeSession.Stm>()

            val guards = privateField<Any>(session.runtime, "guards")
            guards.shouldBeInstanceOf<TimeLimitedGuardEvaluator>()
            privateField<Any>(guards, "delegate").shouldBeInstanceOf<OclGuardEvaluator>()
            privateField<SandboxPolicy>(guards, "policy") shouldBe SandboxPolicy()
        }

        test("ACT sessions sandbox guard evaluation behind TimeLimitedGuardEvaluator(ActivityGuardEvaluator, SandboxPolicy)") {
            val manager = RuntimeSessionManager()
            val started = manager.start(source = actScript, kind = "act", elementName = null)
            started.shouldBeInstanceOf<SessionResult.Started>()

            val session = manager.getSession(started.sessionId)
            session.shouldBeInstanceOf<RuntimeSession.Act>()

            val guardEvaluator = privateField<Any>(session.actRuntime, "guardEvaluator")
            guardEvaluator.shouldBeInstanceOf<TimeLimitedGuardEvaluator>()
            privateField<Any>(guardEvaluator, "delegate").shouldBeInstanceOf<ActivityGuardEvaluator>()
            privateField<SandboxPolicy>(guardEvaluator, "policy") shouldBe SandboxPolicy()
        }
    })
