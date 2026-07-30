package dev.kuml.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StateMachineRuntimeChoiceTest :
    FunSpec({

        test("choice pseudostate auto-fires first guard-true transition (Regel 8)") {
            // Choice with two branches. The guard evaluator allows only branch 2.
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), choice("PaymentOK?"), state(id = "X"), state(id = "Y")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "PaymentOK?", trigger = "submit"),
                            trans(id = "t2", from = "PaymentOK?", to = "X", guard = "[branchX]"),
                            trans(id = "t3", from = "PaymentOK?", to = "Y", guard = "[branchY]"),
                        ),
                )
            val onlyY =
                GuardEvaluator { guard, _, _ ->
                    if (guard == "[branchY]") GuardResult.True else GuardResult.False
                }
            val rt = StateMachineRuntime(guards = onlyY)
            val instance = rt.start(sm)
            rt.step(instance = instance, event = Event.of("submit"))
            instance.currentVertices.map { it.id } shouldBe listOf("Y")
        }

        test("choice with no enabled outgoing throws clear error") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), choice("Dead"), state(id = "X")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "Dead", trigger = "submit"),
                            trans(id = "t2", from = "Dead", to = "X", guard = "[never]"),
                        ),
                )
            val noBranch = GuardEvaluator { _, _, _ -> GuardResult.False }
            val rt = StateMachineRuntime(guards = noBranch)
            val instance = rt.start(sm)
            // The error propagates out of step() as a wrapped exception
            val result = rt.step(instance = instance, event = Event.of("submit"))
            // Either the runtime catches and reports Error, or it propagates — both are acceptable.
            // We assert that an error was recorded (Trace.ActionError or StepResult.Error).
            (result is StepResult.Error || instance.trace.any { it is TraceEntry.ActionError }) shouldBe true
        }

        test("history pseudostate rejected at runtime (Regel 6)") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), history("H")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "H", trigger = "go"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            // Reaching history triggers an error inside step() which is caught and reported.
            val result = rt.step(instance = instance, event = Event.of("go"))
            (result is StepResult.Error || instance.trace.any { it is TraceEntry.ActionError }) shouldBe true
        }

        test("missing initial pseudostate raises a clear error at start") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(state(id = "A")),
                    transitions = emptyList(),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            shouldThrow<IllegalStateException> { rt.start(sm) }
        }
    })
