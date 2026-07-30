package dev.kuml.runtime.sysml2

import dev.kuml.runtime.ActionPhase
import dev.kuml.runtime.Event
import dev.kuml.runtime.TraceEntry
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * V2.0.17 — adapter tests for [Sysml2StateMachineAdapter].
 *
 * The tests build small SysML 2 models via the public DSL, run them through
 * the adapter + [dev.kuml.runtime.StateMachineRuntime] and assert on the
 * trace produced by the runtime. The deterministic epoch clock makes the
 * trace independent of wall-clock time so the assertions stay stable.
 */
class Sysml2StateMachineAdapterTest :
    FunSpec({

        fun epochClock(): () -> Instant {
            val counter = AtomicLong(0L)
            return { Instant.ofEpochMilli(counter.getAndIncrement()) }
        }

        test("adapter builds runtime from minimal flat STM (Initial → Final)") {
            val model =
                sysml2Model(name = "Minimal") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val done = stateDef(name = "Done", isFinal = true)
                    transition(name = "go", source = init, target = done)
                    stmDiagram(name = "Minimal STM") {
                        include(init)
                        include(done)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram

            val handle = Sysml2StateMachineAdapter.runtimeFor(model = model, diagram = stm, clock = epochClock())

            handle.instance.isTerminated shouldBe true
            handle.stateMachine.vertices.map { it.id } shouldContain "Init"
            handle.stateMachine.vertices.map { it.id } shouldContain "Done"
            val visitedFinal =
                handle.instance.trace.any { e ->
                    e is TraceEntry.Terminated && e.finalVertexId == "Done"
                }
            visitedFinal shouldBe true
        }

        test("transition with guard fires only when guard holds") {
            val model =
                sysml2Model(name = "Gated") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "seed", source = init, target = a)
                    transition(name = "toB", source = a, target = b, trigger = "tick", guard = "event.allow")
                    stmDiagram(name = "Gated STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model = model, diagram = stm, clock = epochClock())

            // Guard fails — stays in A
            handle.runtime.step(
                instance = handle.instance,
                event =
                    Event(
                        name = "tick",
                        payload =
                            kotlinx.serialization.json.JsonObject(
                                mapOf("allow" to kotlinx.serialization.json.JsonPrimitive(false)),
                            ),
                    ),
            )
            handle.instance.currentVertices.map { it.id } shouldContain "A"

            // Guard succeeds — transitions to B
            handle.runtime.step(
                instance = handle.instance,
                event =
                    Event(
                        name = "tick",
                        payload =
                            kotlinx.serialization.json.JsonObject(
                                mapOf("allow" to kotlinx.serialization.json.JsonPrimitive(true)),
                            ),
                    ),
            )
            handle.instance.currentVertices.map { it.id } shouldContain "B"
        }

        test("transition with trigger fires on matching event") {
            val model =
                sysml2Model(name = "Triggered") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "seed", source = init, target = a)
                    transition(name = "toB", source = a, target = b, trigger = "go")
                    stmDiagram(name = "Triggered STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model = model, diagram = stm, clock = epochClock())

            // Wrong event — stays in A
            handle.runtime.step(instance = handle.instance, event = Event(name = "noop"))
            handle.instance.currentVertices.map { it.id } shouldContain "A"

            // Matching event — moves to B
            handle.runtime.step(instance = handle.instance, event = Event(name = "go"))
            handle.instance.currentVertices.map { it.id } shouldContain "B"
        }

        test("transition with effect emits the effect string in the trace") {
            val model =
                sysml2Model(name = "Effecting") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "seed", source = init, target = a)
                    transition(name = "toB", source = a, target = b, trigger = "go", effect = "logSwitch()")
                    stmDiagram(name = "Effecting STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model = model, diagram = stm, clock = epochClock())

            handle.runtime.step(instance = handle.instance, event = Event(name = "go"))

            val effectEntries =
                handle.instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.EFFECT }
            effectEntries.size shouldBe 1
            effectEntries.first().action shouldBe "logSwitch()"
        }

        test("state with entryAction emits entry trace entry on activation") {
            val model =
                sysml2Model(name = "EntryAction") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A", entryAction = "logEnterA()")
                    transition(name = "seed", source = init, target = a)
                    stmDiagram(name = "EntryAction STM") {
                        include(init)
                        include(a)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model = model, diagram = stm, clock = epochClock())

            val entryActions =
                handle.instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.ENTRY && it.vertexId == "A" }
            entryActions.size shouldBe 1
            entryActions.first().action shouldBe "logEnterA()"
        }

        test("state with exitAction emits exit trace entry on deactivation") {
            val model =
                sysml2Model(name = "ExitAction") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A", exitAction = "logExitA()")
                    val b = stateDef(name = "B")
                    transition(name = "seed", source = init, target = a)
                    transition(name = "toB", source = a, target = b, trigger = "go")
                    stmDiagram(name = "ExitAction STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model = model, diagram = stm, clock = epochClock())

            handle.runtime.step(instance = handle.instance, event = Event(name = "go"))

            val exitActions =
                handle.instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.EXIT && it.vertexId == "A" }
            exitActions.size shouldBe 1
            exitActions.first().action shouldBe "logExitA()"
        }

        test("STM with no initial state — adapter throws a clear error") {
            val model =
                sysml2Model(name = "NoInitial") {
                    val a = stateDef(name = "A")
                    val b = stateDef(name = "B")
                    transition(name = "toB", source = a, target = b)
                    stmDiagram(name = "NoInitial STM") {
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram

            val ex =
                shouldThrow<IllegalStateException> {
                    Sysml2StateMachineAdapter.toUmlStateMachine(model = model, diagram = stm)
                }
            ex.message!! shouldContain "no visible initial state"
        }

        test("transitions whose endpoints are not in the diagram are silently dropped") {
            val model =
                sysml2Model(name = "Filtered") {
                    val init = stateDef(name = "Init", isInitial = true)
                    val a = stateDef(name = "A")
                    val hidden = stateDef(name = "Hidden")
                    transition(name = "seed", source = init, target = a)
                    transition(name = "toHidden", source = a, target = hidden, trigger = "leak")
                    // Hidden NOT included in the diagram
                    stmDiagram(name = "Filtered STM") {
                        include(init)
                        include(a)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val machine = Sysml2StateMachineAdapter.toUmlStateMachine(model = model, diagram = stm)

            // Only the seed transition survives the visible-endpoint filter
            machine.transitions.map { it.id } shouldBe listOf("transition:Init::A")
        }
    })
