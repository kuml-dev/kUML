package dev.kuml.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StateMachineRuntimeAtomicTest :
    FunSpec({

        test("guard evaluator throwing an exception rolls back state (Regel 5)") {
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), state(id = "B")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "B", trigger = "go", guard = "boom"),
                        ),
                )
            val throwingGuard =
                GuardEvaluator { _, _, _ -> throw IllegalStateException("guard panic") }
            val rt = StateMachineRuntime(guards = throwingGuard)
            val instance = rt.start(sm)
            val snapshotIds = instance.currentVertices.map { it.id }
            val result = rt.step(instance = instance, event = Event.of("go"))
            result.shouldBeInstanceOf<StepResult.Error>()
            // State unchanged after rollback
            instance.currentVertices.map { it.id } shouldBe snapshotIds
            // Trace contains an ActionError entry
            instance.trace.any { it is TraceEntry.ActionError } shouldBe true
        }

        test("snapshot and restore roundtrip preserves currentVertices and variables") {
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
            instance.variables["x"] = 42
            instance.variables["name"] = "alice"
            rt.step(instance = instance, event = Event.of("go"))

            val snap = rt.snapshot(instance)
            val restored = rt.restore(model = sm, snapshot = snap)

            restored.currentVertices.map { it.id } shouldBe instance.currentVertices.map { it.id }
            restored.variables["x"] shouldBe 42L
            restored.variables["name"] shouldBe "alice"
        }

        test("internal queue is drained before step returns") {
            // V1.1.5 has no way to enqueue internal events from actions yet, but the mechanism
            // must work — we inject one manually after start() and verify drain on next step.
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial(), state(id = "A"), state(id = "B"), state(id = "C")),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "init", to = "A"),
                            trans(id = "t1", from = "A", to = "B", trigger = "go"),
                            trans(id = "t2", from = "B", to = "C", trigger = "auto"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            // Step takes us A → B; we *pre-load* the internal queue so the drain follows up to C.
            instance.mutInternalQueue.addLast(Event.of("auto"))
            rt.step(instance = instance, event = Event.of("go"))
            instance.currentVertices.map { it.id } shouldBe listOf("C")
            instance.mutInternalQueue.isEmpty() shouldBe true
        }
    })
