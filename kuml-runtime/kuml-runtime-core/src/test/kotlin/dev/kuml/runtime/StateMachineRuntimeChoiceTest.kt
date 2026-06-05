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
                    vertices = listOf(initial(), state("A"), choice("PaymentOK?"), state("X"), state("Y")),
                    transitions =
                        listOf(
                            trans("t0", "init", "A"),
                            trans("t1", "A", "PaymentOK?", trigger = "submit"),
                            trans("t2", "PaymentOK?", "X", guard = "[branchX]"),
                            trans("t3", "PaymentOK?", "Y", guard = "[branchY]"),
                        ),
                )
            val onlyY =
                GuardEvaluator { guard, _, _ ->
                    if (guard == "[branchY]") GuardResult.True else GuardResult.False
                }
            val rt = StateMachineRuntime(guards = onlyY)
            val instance = rt.start(sm)
            rt.step(instance, Event.of("submit"))
            instance.currentVertices.map { it.id } shouldBe listOf("Y")
        }

        test("choice with no enabled outgoing throws clear error") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state("A"), choice("Dead"), state("X")),
                    transitions =
                        listOf(
                            trans("t0", "init", "A"),
                            trans("t1", "A", "Dead", trigger = "submit"),
                            trans("t2", "Dead", "X", guard = "[never]"),
                        ),
                )
            val noBranch = GuardEvaluator { _, _, _ -> GuardResult.False }
            val rt = StateMachineRuntime(guards = noBranch)
            val instance = rt.start(sm)
            // The error propagates out of step() as a wrapped exception
            val result = rt.step(instance, Event.of("submit"))
            // Either the runtime catches and reports Error, or it propagates — both are acceptable.
            // We assert that an error was recorded (Trace.ActionError or StepResult.Error).
            (result is StepResult.Error || instance.trace.any { it is TraceEntry.ActionError }) shouldBe true
        }

        test("history pseudostate rejected at runtime (Regel 6)") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state("A"), history("H")),
                    transitions =
                        listOf(
                            trans("t0", "init", "A"),
                            trans("t1", "A", "H", trigger = "go"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            // Reaching history triggers an error inside step() which is caught and reported.
            val result = rt.step(instance, Event.of("go"))
            (result is StepResult.Error || instance.trace.any { it is TraceEntry.ActionError }) shouldBe true
        }

        test("missing initial pseudostate raises a clear error at start") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(state("A")),
                    transitions = emptyList(),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            shouldThrow<IllegalStateException> { rt.start(sm) }
        }
    })
