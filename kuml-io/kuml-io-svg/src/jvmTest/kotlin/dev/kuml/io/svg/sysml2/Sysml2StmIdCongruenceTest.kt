package dev.kuml.io.svg.sysml2

import dev.kuml.layout.bridge.Sysml2LayoutBridge
import dev.kuml.runtime.sysml2.Sysml2StateMachineAdapter
import dev.kuml.sysml2.StmDiagram
import dev.kuml.sysml2.Sysml2Model
import dev.kuml.sysml2.dsl.sysml2Model
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * V3.x — Live-Simulation von Zustandsautomaten im Editor.
 *
 * Freezes the assumption the whole desktop-simulation wave stands on: the vertex IDs
 * [Sysml2LayoutBridge.toLayoutGraph] uses for `LayoutResult.nodes` keys are IDENTICAL to the
 * vertex IDs [Sysml2StateMachineAdapter.toUmlStateMachine] puts into `UmlStateMachine.vertices`
 * — both derive straight from `StateDefinition.id` with no re-mapping. Without this, the desktop
 * could not correlate "active vertex per the runtime" with "highlight-ring-<id> in the SVG" —
 * see `SimulationSupport.kt`'s KDoc reference to this test (Spez. G32).
 */
class Sysml2StmIdCongruenceTest :
    StringSpec({

        fun trafficLightModel(): Pair<Sysml2Model, StmDiagram> {
            val model =
                sysml2Model(name = "TrafficLight") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    val final = stateDef(name = "Final", isFinal = true)
                    transition(name = "init", source = initial, target = red)
                    transition(name = "redToGreen", source = red, target = green, trigger = "timer60s")
                    transition(name = "greenToYellow", source = green, target = yellow, trigger = "timer45s")
                    transition(name = "yellowToRed", source = yellow, target = red, trigger = "timer5s")
                    transition(name = "powerOff", source = red, target = final, trigger = "powerOff")
                    stmDiagram(name = "Phase cycle") {
                        include(initial)
                        include(red)
                        include(green)
                        include(yellow)
                        include(final)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()
            return model to stm
        }

        "layout node IDs equal runtime vertex IDs for the full traffic-light model" {
            val (model, stm) = trafficLightModel()

            val nodeIds =
                Sysml2LayoutBridge
                    .toLayoutGraph(model = model, diagram = stm)
                    .nodes
                    .map { it.id.value }
                    .toSet()
            val vertexIds =
                Sysml2StateMachineAdapter
                    .toUmlStateMachine(model = model, diagram = stm)
                    .vertices
                    .map { it.id }
                    .toSet()

            nodeIds shouldBe vertexIds
        }

        "layout node IDs equal runtime vertex IDs for a projected subset of states" {
            // "Pattern A" projection: the STM diagram includes only a subset of the model's
            // states (Initial + Red + Green — Yellow/Final excluded from this view).
            val model =
                sysml2Model(name = "Partial") {
                    val initial = stateDef(name = "Initial", isInitial = true)
                    val red = stateDef(name = "Red")
                    val green = stateDef(name = "Green")
                    val yellow = stateDef(name = "Yellow")
                    transition(name = "init", source = initial, target = red)
                    transition(name = "redToGreen", source = red, target = green, trigger = "next")
                    transition(name = "greenToYellow", source = green, target = yellow, trigger = "next")
                    stmDiagram(name = "Partial view") {
                        include(initial)
                        include(red)
                        include(green)
                    }
                }
            val stm = model.diagrams.filterIsInstance<StmDiagram>().single()

            val nodeIds =
                Sysml2LayoutBridge
                    .toLayoutGraph(model = model, diagram = stm)
                    .nodes
                    .map { it.id.value }
                    .toSet()
            val vertexIds =
                Sysml2StateMachineAdapter
                    .toUmlStateMachine(model = model, diagram = stm)
                    .vertices
                    .map { it.id }
                    .toSet()

            nodeIds shouldBe vertexIds
            nodeIds shouldBe setOf("Initial", "Red", "Green")
        }
    })
