package dev.kuml.widget.compose

import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Tests for how [BehaviourWidgetState] reacts to a successful [changeGuard] —
 * specifically the two behaviors the Wave 5 plan called out that are easy to
 * get wrong because [BehaviourWidgetState.reset] restores the *instance* from
 * [BehaviourWidgetState] internals, but keeps the *model* (which [changeGuard]
 * already swapped) as-is:
 *
 * 1. [BehaviourWidgetState.reset] does NOT roll back an edited guard — only the
 *    live simulation (active vertices / trace) is restored, not the model.
 * 2. [BehaviourWidgetState.trace] / [BehaviourWidgetState.tracePosition] stay
 *    consistent (re-synced to the live instance) immediately after an edit,
 *    without requiring a [BehaviourWidgetState.sendEvent] round-trip first.
 *
 * Fixture: traffic-light machine `init -> Red --next--> Green --next--> Yellow
 * --next--> Red`, guard editing enabled via [EditPolicy.GuardsOnly].
 */
class BehaviourWidgetStateModelChangeTest :
    FunSpec({

        fun buildTrafficLight(): UmlStateMachine {
            val initial = UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL)
            val red = UmlState(id = "Red", name = "Red")
            val green = UmlState(id = "Green", name = "Green")
            val yellow = UmlState(id = "Yellow", name = "Yellow")
            return UmlStateMachine(
                id = "traffic-light",
                name = "Traffic Light",
                vertices = listOf(initial, red, green, yellow),
                transitions =
                    listOf(
                        UmlTransition(id = "t-init-red", sourceId = "init", targetId = "Red"),
                        UmlTransition(id = "t-red-green", sourceId = "Red", targetId = "Green", trigger = "next"),
                        UmlTransition(id = "t-green-yellow", sourceId = "Green", targetId = "Yellow", trigger = "next"),
                        UmlTransition(id = "t-yellow-red", sourceId = "Yellow", targetId = "Red", trigger = "next"),
                    ),
            )
        }

        fun buildState(): BehaviourWidgetState {
            val model = buildTrafficLight()
            val runtime = StateMachineRuntime()
            return BehaviourWidgetState(initialModel = model, runtime = runtime, editPolicy = EditPolicy.GuardsOnly)
        }

        fun UmlStateMachine.transition(id: String): UmlTransition = transitions.first { it.id == id }

        test("reset() after a guard edit keeps the edited guard (model is not rolled back)") {
            val state = buildState()

            // "true" (rather than an unresolved "vars.ready") so the transition still
            // actually fires below — the point of this test is reset()'s effect on the
            // *model*, not on guard evaluation.
            state.changeGuard("t-red-green", "true") shouldBe PatchOutcome.Applied
            state.model.transition("t-red-green").guard shouldBe "true"

            // Advance the live simulation so reset() has something to actually roll back.
            state.sendEvent("next")
            state.currentHighlightIds() shouldContain "Green"

            state.reset()

            // The simulation (active vertex / trace) is back at the start ...
            state.currentHighlightIds() shouldContain "Red"
            state.isScrubbing.shouldBeFalse()
            // ... but the edited guard on the model survives the reset.
            state.model.transition("t-red-green").guard shouldBe "true"
        }

        test("trace stays consistent (re-synced, live) immediately after a successful guard edit") {
            val state = buildState()
            state.sendEvent("next") // Red -> Green, so there is a non-trivial trace to preserve

            val traceBefore = state.trace
            val positionBefore = state.tracePosition

            val outcome = state.changeGuard("t-green-yellow", "true")

            outcome shouldBe PatchOutcome.Applied
            // syncTrace() re-derives trace/tracePosition from the (rebuilt) live instance —
            // the recorded history up to the edit must not be dropped or duplicated.
            state.trace shouldBe traceBefore
            state.trace.size shouldBe traceBefore.size
            state.tracePosition shouldBe state.trace.size
            state.isScrubbing.shouldBeFalse()
            positionBefore shouldBe traceBefore.size

            // The live simulation is still fully functional after the edit: sending
            // another event advances the (now guard-patched) machine as expected.
            state.sendEvent("next")
            state.currentHighlightIds() shouldContain "Yellow"
        }
    })
