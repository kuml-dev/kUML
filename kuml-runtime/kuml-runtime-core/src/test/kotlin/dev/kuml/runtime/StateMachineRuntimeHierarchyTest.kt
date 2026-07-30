package dev.kuml.runtime

import dev.kuml.uml.UmlStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class StateMachineRuntimeHierarchyTest :
    FunSpec({

        /**
         * Build a SM with:
         *   init → Processing  (Composite with initial → Picking → Packing)
         *   Picking --[done]--> Packing  (transition inside composite)
         *   Processing --[cancel]--> Draft  (transition out of composite)
         */
        fun compositeMachine(): UmlStateMachine {
            val procInitial = initial("procInit")
            val picking = state(id = "Picking", entry = "startPick", exit = "stopPick")
            val packing = state(id = "Packing", entry = "startPack")
            val processing =
                state(
                    id = "Processing",
                    entry = "startProc",
                    exit = "stopProc",
                    substates = listOf(procInitial, picking, packing),
                )
            val draft = state(id = "Draft")
            return smOf(
                name = "M",
                vertices = listOf(initial("rootInit"), draft, processing),
                transitions =
                    listOf(
                        trans(id = "t0", from = "rootInit", to = "Processing"),
                        trans(id = "tProcInit", from = "procInit", to = "Picking"),
                        trans(id = "tDone", from = "Picking", to = "Packing", trigger = "done"),
                        trans(id = "tCancel", from = "Processing", to = "Draft", trigger = "cancel"),
                    ),
            )
        }

        test("entry actions fire top-down through composite hierarchy") {
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(compositeMachine())
            val entries =
                instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.ENTRY }
                    .map { it.action }
            entries shouldBe listOf("startProc", "startPick")
        }

        test("currentVertices contains full composite path") {
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(compositeMachine())
            instance.currentVertices.map { it.id } shouldBe listOf("Processing", "Picking")
        }

        test("transition between substates of same composite keeps composite active") {
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(compositeMachine())
            rt.step(instance = instance, event = Event.of("done"))
            instance.currentVertices.map { it.id } shouldBe listOf("Processing", "Packing")
            // Processing.exit must NOT appear
            instance.trace
                .filterIsInstance<TraceEntry.ActionInvoked>()
                .any { it.phase == ActionPhase.EXIT && it.action == "stopProc" } shouldBe false
        }

        test("exit actions fire bottom-up to LCA when leaving composite") {
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(compositeMachine())
            rt.step(instance = instance, event = Event.of("cancel"))
            val exits =
                instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.EXIT }
                    .map { it.action }
            // Bottom-up: Picking.exit, then Processing.exit
            exits shouldBe listOf("stopPick", "stopProc")
            instance.currentVertices.map { it.id } shouldBe listOf("Draft")
        }

        test("deepest source wins when multiple transitions are enabled (Regel 2b)") {
            // Two transitions trigger "go": one from Picking (deeper) → X, one from Processing (shallower) → Y.
            val procInitial = initial("procInit")
            val picking = state(id = "Picking")
            val processing =
                state(
                    id = "Processing",
                    substates = listOf(procInitial, picking),
                )
            val x = state(id = "X")
            val y = state(id = "Y")
            val sm =
                smOf(
                    name = "M",
                    vertices = listOf(initial("rootInit"), processing, x, y),
                    transitions =
                        listOf(
                            trans(id = "t0", from = "rootInit", to = "Processing"),
                            trans(id = "tProcInit", from = "procInit", to = "Picking"),
                            trans(id = "tShallow", from = "Processing", to = "Y", trigger = "go"),
                            trans(id = "tDeep", from = "Picking", to = "X", trigger = "go"),
                        ),
                )
            val rt = StateMachineRuntime(guards = GuardEvaluator.AlwaysTrue)
            val instance = rt.start(sm)
            rt.step(instance = instance, event = Event.of("go"))
            instance.currentVertices.map { it.id } shouldHaveSize 1
            instance.currentVertices.first().id shouldBe "X"
        }
    })
