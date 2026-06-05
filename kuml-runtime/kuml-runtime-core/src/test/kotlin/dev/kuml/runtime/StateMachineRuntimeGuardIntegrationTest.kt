package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class StateMachineRuntimeGuardIntegrationTest :
    FunSpec({

        test("guard event.amount > 100 enables transition with matching payload") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state("A"), state("B")),
                    transitions =
                        listOf(
                            trans("t0", "init", "A"),
                            trans(
                                "t1",
                                "A",
                                "B",
                                trigger = "pay",
                                guard = "event.amount > 100",
                            ),
                        ),
                )
            // Default constructor wires in OclGuardEvaluator
            val rt = StateMachineRuntime()
            val instance = rt.start(sm)
            val largeEvent =
                Event(
                    name = "pay",
                    payload = buildJsonObject { put("amount", JsonPrimitive(150)) },
                )
            val result = rt.step(instance, largeEvent)
            result.shouldBeInstanceOf<StepResult.Transitioned>()
            instance.currentVertices.first().id shouldBe "B"
        }

        test("guard event.amount > 100 stays for small payload") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state("A"), state("B")),
                    transitions =
                        listOf(
                            trans("t0", "init", "A"),
                            trans(
                                "t1",
                                "A",
                                "B",
                                trigger = "pay",
                                guard = "event.amount > 100",
                            ),
                        ),
                )
            val rt = StateMachineRuntime()
            val instance = rt.start(sm)
            val smallEvent =
                Event(
                    name = "pay",
                    payload = buildJsonObject { put("amount", JsonPrimitive(50)) },
                )
            val result = rt.step(instance, smallEvent)
            result.shouldBeInstanceOf<StepResult.Stayed>()
            instance.currentVertices.first().id shouldBe "A"
        }

        test("square-bracketed guard works just like the unbracketed form") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state("A"), state("B")),
                    transitions =
                        listOf(
                            trans("t0", "init", "A"),
                            trans(
                                "t1",
                                "A",
                                "B",
                                trigger = "pay",
                                guard = "[event.amount > 100]",
                            ),
                        ),
                )
            val rt = StateMachineRuntime()
            val instance = rt.start(sm)
            rt.step(
                instance,
                Event(name = "pay", payload = buildJsonObject { put("amount", JsonPrimitive(200)) }),
            )
            instance.currentVertices.first().id shouldBe "B"
        }
    })
