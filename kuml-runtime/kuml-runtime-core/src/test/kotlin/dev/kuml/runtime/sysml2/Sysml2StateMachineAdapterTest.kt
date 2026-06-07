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
                sysml2Model("Minimal") {
                    val init = stateDef("Init", isInitial = true)
                    val done = stateDef("Done", isFinal = true)
                    transition("go", init, done)
                    stmDiagram("Minimal STM") {
                        include(init)
                        include(done)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram

            val handle = Sysml2StateMachineAdapter.runtimeFor(model, stm, clock = epochClock())

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
                sysml2Model("Gated") {
                    val init = stateDef("Init", isInitial = true)
                    val a = stateDef("A")
                    val b = stateDef("B")
                    transition("seed", init, a)
                    transition("toB", a, b, trigger = "tick", guard = "event.allow")
                    stmDiagram("Gated STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model, stm, clock = epochClock())

            // Guard fails — stays in A
            handle.runtime.step(
                handle.instance,
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
                handle.instance,
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
                sysml2Model("Triggered") {
                    val init = stateDef("Init", isInitial = true)
                    val a = stateDef("A")
                    val b = stateDef("B")
                    transition("seed", init, a)
                    transition("toB", a, b, trigger = "go")
                    stmDiagram("Triggered STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model, stm, clock = epochClock())

            // Wrong event — stays in A
            handle.runtime.step(handle.instance, Event(name = "noop"))
            handle.instance.currentVertices.map { it.id } shouldContain "A"

            // Matching event — moves to B
            handle.runtime.step(handle.instance, Event(name = "go"))
            handle.instance.currentVertices.map { it.id } shouldContain "B"
        }

        test("transition with effect emits the effect string in the trace") {
            val model =
                sysml2Model("Effecting") {
                    val init = stateDef("Init", isInitial = true)
                    val a = stateDef("A")
                    val b = stateDef("B")
                    transition("seed", init, a)
                    transition("toB", a, b, trigger = "go", effect = "logSwitch()")
                    stmDiagram("Effecting STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model, stm, clock = epochClock())

            handle.runtime.step(handle.instance, Event(name = "go"))

            val effectEntries =
                handle.instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.EFFECT }
            effectEntries.size shouldBe 1
            effectEntries.first().action shouldBe "logSwitch()"
        }

        test("state with entryAction emits entry trace entry on activation") {
            val model =
                sysml2Model("EntryAction") {
                    val init = stateDef("Init", isInitial = true)
                    val a = stateDef("A", entryAction = "logEnterA()")
                    transition("seed", init, a)
                    stmDiagram("EntryAction STM") {
                        include(init)
                        include(a)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model, stm, clock = epochClock())

            val entryActions =
                handle.instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.ENTRY && it.vertexId == "A" }
            entryActions.size shouldBe 1
            entryActions.first().action shouldBe "logEnterA()"
        }

        test("state with exitAction emits exit trace entry on deactivation") {
            val model =
                sysml2Model("ExitAction") {
                    val init = stateDef("Init", isInitial = true)
                    val a = stateDef("A", exitAction = "logExitA()")
                    val b = stateDef("B")
                    transition("seed", init, a)
                    transition("toB", a, b, trigger = "go")
                    stmDiagram("ExitAction STM") {
                        include(init)
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val handle = Sysml2StateMachineAdapter.runtimeFor(model, stm, clock = epochClock())

            handle.runtime.step(handle.instance, Event(name = "go"))

            val exitActions =
                handle.instance.trace
                    .filterIsInstance<TraceEntry.ActionInvoked>()
                    .filter { it.phase == ActionPhase.EXIT && it.vertexId == "A" }
            exitActions.size shouldBe 1
            exitActions.first().action shouldBe "logExitA()"
        }

        test("STM with no initial state — adapter throws a clear error") {
            val model =
                sysml2Model("NoInitial") {
                    val a = stateDef("A")
                    val b = stateDef("B")
                    transition("toB", a, b)
                    stmDiagram("NoInitial STM") {
                        include(a)
                        include(b)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram

            val ex =
                shouldThrow<IllegalStateException> {
                    Sysml2StateMachineAdapter.toUmlStateMachine(model, stm)
                }
            ex.message!! shouldContain "no visible initial state"
        }

        test("transitions whose endpoints are not in the diagram are silently dropped") {
            val model =
                sysml2Model("Filtered") {
                    val init = stateDef("Init", isInitial = true)
                    val a = stateDef("A")
                    val hidden = stateDef("Hidden")
                    transition("seed", init, a)
                    transition("toHidden", a, hidden, trigger = "leak")
                    // Hidden NOT included in the diagram
                    stmDiagram("Filtered STM") {
                        include(init)
                        include(a)
                    }
                }
            val stm = model.diagrams.first() as dev.kuml.sysml2.StmDiagram
            val machine = Sysml2StateMachineAdapter.toUmlStateMachine(model, stm)

            // Only the seed transition survives the visible-endpoint filter
            machine.transitions.map { it.id } shouldBe listOf("transition:Init::A")
        }
    })
