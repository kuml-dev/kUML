package dev.kuml.widget.compose

import dev.kuml.core.model.KumlMetaValue
import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.TransitionMetadataKeys
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * End-to-end tests for [BehaviourWidgetState.changeGuard] — drives the real
 * [StateMachineRuntime]/`applyPatch` path (Wave 5), not a mock.
 *
 * Fixture: traffic-light machine `init -> Red --next--> Green --next--> Yellow
 * --next--> Red`, where `t-red-green` is a plain (non-protected) guarded
 * transition and `t-yellow-red` is protected.
 */
class ChangeGuardBridgeTest :
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
                        UmlTransition(
                            id = "t-yellow-red",
                            sourceId = "Yellow",
                            targetId = "Red",
                            trigger = "next",
                            metadata = mapOf(TransitionMetadataKeys.PROTECTED to KumlMetaValue.Flag(true)),
                        ),
                    ),
            )
        }

        fun buildState(editPolicy: EditPolicy = EditPolicy.GuardsOnly): BehaviourWidgetState {
            val model = buildTrafficLight()
            val runtime = StateMachineRuntime()
            return BehaviourWidgetState(initialModel = model, runtime = runtime, editPolicy = editPolicy)
        }

        fun UmlStateMachine.transition(id: String): UmlTransition = transitions.first { it.id == id }

        test("normal transition under GuardsOnly is applied, swaps model, and re-renders with the new guard") {
            val state = buildState(EditPolicy.GuardsOnly)
            val originalModel = state.model

            val outcome = state.changeGuard("t-red-green", "vars.ready")

            outcome shouldBe PatchOutcome.Applied
            state.model shouldNotBe originalModel
            state.model.transition("t-red-green").guard shouldBe "vars.ready"

            val layout = computeLayout(state.model)
            val svg = renderStateMachineSvg(state.model, layout, emptySet())
            // Guard is rendered verbatim from the DSL (no brackets added by the
            // STATE renderer — see KumlSvgRenderer's edge-label assembly), so we
            // assert on the guard text itself rather than a `[...]`-wrapped form.
            svg shouldContain "vars.ready"
        }

        test("EditPolicy.None rejects the edit and leaves the model untouched") {
            val state = buildState(EditPolicy.None)
            val originalModel = state.model

            val outcome = state.changeGuard("t-red-green", "vars.ready")

            outcome.shouldBeInstanceOf<PatchOutcome.Rejected>()
            outcome.message shouldContain "not permitted"
            state.model shouldBe originalModel
        }

        test("protected transition without confirmation needs confirmation and leaves the model untouched") {
            val state = buildState(EditPolicy.GuardsOnly)
            val originalModel = state.model

            val outcome = state.changeGuard("t-yellow-red", "true")

            outcome shouldBe PatchOutcome.NeedsConfirmation
            state.model shouldBe originalModel
        }

        test("protected transition with confirmation is applied") {
            val state = buildState(EditPolicy.GuardsOnly)

            val outcome = state.changeGuard("t-yellow-red", "true", confirmed = true)

            outcome shouldBe PatchOutcome.Applied
            state.model.transition("t-yellow-red").guard shouldBe "true"
        }

        test("invalid OCL is rejected and leaves the model untouched") {
            val state = buildState(EditPolicy.GuardsOnly)
            val originalModel = state.model

            val outcome = state.changeGuard("t-red-green", "nope > 0")

            outcome.shouldBeInstanceOf<PatchOutcome.Rejected>()
            state.model shouldBe originalModel
        }

        test("oversized OCL is rejected by the static size guard") {
            val state = buildState(EditPolicy.GuardsOnly)
            val originalModel = state.model
            val huge = "a".repeat(5000)

            val outcome = state.changeGuard("t-red-green", huge)

            outcome.shouldBeInstanceOf<PatchOutcome.Rejected>()
            outcome.message shouldContain "too long"
            state.model shouldBe originalModel
        }

        test("guard edits are blocked while scrubbing, and succeed again once back to live") {
            val state = buildState(EditPolicy.GuardsOnly)
            val originalModel = state.model
            state.scrubTo(0)
            state.isScrubbing shouldBe true

            val blocked = state.changeGuard("t-red-green", "vars.ready")
            blocked.shouldBeInstanceOf<PatchOutcome.Rejected>()
            blocked.message shouldContain "scrubbing"
            state.model shouldBe originalModel

            state.scrubTo(state.trace.size)
            state.isScrubbing shouldBe false

            val applied = state.changeGuard("t-red-green", "vars.ready")
            applied shouldBe PatchOutcome.Applied
            state.model.transition("t-red-green").guard shouldBe "vars.ready"
        }

        test("clearing a guard on a guarded transition applies and results in a null guard") {
            val state = buildState(EditPolicy.GuardsOnly)
            state.changeGuard("t-red-green", "vars.ready") shouldBe PatchOutcome.Applied
            state.model.transition("t-red-green").guard shouldBe "vars.ready"

            val outcome = state.changeGuard("t-red-green", "")

            outcome shouldBe PatchOutcome.Applied
            state.model.transition("t-red-green").guard shouldBe null
        }
    })
