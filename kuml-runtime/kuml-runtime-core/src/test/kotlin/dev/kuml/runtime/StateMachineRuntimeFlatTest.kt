package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StateMachineRuntimeFlatTest :
    FunSpec({

        test("start enters initial state via implicit transition (Regel 1)") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A", entry = "onA")),
                    transitions = listOf(trans(id = "t0", from = "init", to = "A")),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            instance.currentVertices.map { it.id } shouldBe listOf("A")
            instance.trace.any { it is TraceEntry.ActionInvoked && it.action == "onA" } shouldBe true
        }

        test("step matches trigger and transitions") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), state(id = "B")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "B", trigger = "go"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            val result = rt.step(instance = instance, event = Event.of("go"))
            result.shouldBeInstanceOf<StepResult.Transitioned>()
            instance.currentVertices.map { it.id } shouldBe listOf("B")
        }

        test("step Stayed on unknown event (Regel 3)") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), state(id = "B")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "B", trigger = "go"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            val result = rt.step(instance = instance, event = Event.of("nope"))
            result.shouldBeInstanceOf<StepResult.Stayed>()
            instance.currentVertices.map { it.id } shouldBe listOf("A")
        }

        test("step terminates on FinalState (Regel 9)") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), finalState("Done")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "Done", trigger = "finish"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            val result = rt.step(instance = instance, event = Event.of("finish"))
            result shouldBe StepResult.Terminated
            instance.isTerminated shouldBe true
        }

        test("subsequent step on terminated machine returns Stayed") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), finalState("Done")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "Done", trigger = "finish"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            rt.step(instance = instance, event = Event.of("finish"))
            val again = rt.step(instance = instance, event = Event.of("anything"))
            again.shouldBeInstanceOf<StepResult.Stayed>()
            again.reason shouldBe "state machine terminated"
        }

        test("trigger arguments are ignored — only the name before '(' matters") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), state(id = "B")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "B", trigger = "pay(amount)"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            val result = rt.step(instance = instance, event = Event.of("pay"))
            result.shouldBeInstanceOf<StepResult.Transitioned>()
            instance.currentVertices.map { it.id } shouldHaveSize 1
            instance.currentVertices.first().id shouldBe "B"
        }
    })
