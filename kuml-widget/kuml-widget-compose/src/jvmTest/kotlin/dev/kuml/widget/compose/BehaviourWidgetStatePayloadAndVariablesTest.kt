package dev.kuml.widget.compose

import dev.kuml.runtime.StateMachineRuntime
import dev.kuml.runtime.StepResult
import dev.kuml.uml.PseudostateKind
import dev.kuml.uml.UmlPseudostate
import dev.kuml.uml.UmlState
import dev.kuml.uml.UmlStateMachine
import dev.kuml.uml.UmlTransition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive

/**
 * Payload delivery, variable editing, fork-with-variables, [BehaviourWidgetState.resetAll] and
 * [revertGuard] on a machine `init -> A --go[event.amount > 5]--> B`, `A --alt--> C`.
 */
class BehaviourWidgetStatePayloadAndVariablesTest :
    FunSpec({

        fun machine(goGuard: String? = "event.amount > 5"): UmlStateMachine =
            UmlStateMachine(
                id = "m",
                name = "M",
                vertices =
                    listOf(
                        UmlPseudostate(id = "init", name = "init", kind = PseudostateKind.INITIAL),
                        UmlState(id = "A", name = "A"),
                        UmlState(id = "B", name = "B"),
                        UmlState(id = "C", name = "C"),
                    ),
                transitions =
                    listOf(
                        UmlTransition(id = "t-init", sourceId = "init", targetId = "A"),
                        UmlTransition(id = "t-go", sourceId = "A", targetId = "B", trigger = "go", guard = goGuard),
                        UmlTransition(id = "t-alt", sourceId = "A", targetId = "C", trigger = "alt"),
                        UmlTransition(id = "t-back", sourceId = "B", targetId = "A", trigger = "back"),
                    ),
            )

        fun state(
            model: UmlStateMachine = machine(),
            policy: EditPolicy = EditPolicy.GuardsOnly,
        ) = BehaviourWidgetState(initialModel = model, runtime = StateMachineRuntime(), editPolicy = policy)

        // ── payload ──────────────────────────────────────────────────────────

        test("a payload value above the threshold fires the guarded transition") {
            val s = state()
            s.sendEvent(eventName = "go", payloadJson = """{"amount": 10}""")
            s.currentHighlightIds() shouldBe setOf("B")
        }

        test("a payload value below the threshold does not fire it") {
            val s = state()
            s.sendEvent(eventName = "go", payloadJson = """{"amount": 1}""")
            s.currentHighlightIds() shouldBe setOf("A")
        }

        test("both comparison directions work for Int payload numbers against Long literals") {
            val a = state(model = machine(goGuard = "event.amount < 5"))
            a.sendEvent(eventName = "go", payloadJson = """{"amount": 1}""")
            a.currentHighlightIds() shouldBe setOf("B")
            val b = state(model = machine(goGuard = "5 < event.amount"))
            b.sendEvent(eventName = "go", payloadJson = """{"amount": 9}""")
            b.currentHighlightIds() shouldBe setOf("B")
        }

        test("an invalid payload throws and leaves trace and position untouched") {
            val s = state()
            val traceBefore = s.trace
            val posBefore = s.tracePosition
            val ex = shouldThrow<InvalidEventPayloadException> { s.sendEvent(eventName = "go", payloadJson = "{nope") }
            ex.result.error shouldBe PayloadError.SYNTAX
            s.trace shouldBe traceBefore
            s.tracePosition shouldBe posBefore
        }

        test("an invalid payload while scrubbing does not fork") {
            val s = state()
            s.sendEvent(eventName = "alt")
            s.scrubTo(1)
            val traceBefore = s.trace
            shouldThrow<InvalidEventPayloadException> { s.sendEvent(eventName = "go", payloadJson = "[1]") }
            s.trace shouldBe traceBefore
            s.tracePosition shouldBe 1
            s.isScrubbing shouldBe true
        }

        // ── variables ────────────────────────────────────────────────────────

        test("setVariable and removeVariable update liveVariables") {
            val s = state()
            s.setVariable(name = "x", value = JsonPrimitive(3)) shouldBe VariableEditOutcome.Applied
            s.liveVariables["x"] shouldBe JsonPrimitive(3L)
            s.removeVariable("x") shouldBe VariableEditOutcome.Applied
            s.liveVariables.containsKey("x") shouldBe false
        }

        test("a variable set before sending is visible to guards") {
            val s = state(model = machine(goGuard = "vars.ready == true"))
            s.setVariable(name = "ready", value = JsonPrimitive(true))
            s.sendEvent(eventName = "go")
            s.currentHighlightIds() shouldBe setOf("B")
        }

        test("variable names are validated") {
            val s = state()
            for (bad in listOf("", "1a", "a-b", "a b", "a".repeat(65))) {
                s.setVariable(name = bad, value = JsonPrimitive(1)) shouldBe VariableEditOutcome.InvalidName
            }
            s.setVariable(name = "a".repeat(64), value = JsonPrimitive(1)) shouldBe VariableEditOutcome.Applied
        }

        test("at most 64 variables, existing ones can still be overwritten") {
            val s = state()
            repeat(64) { s.setVariable(name = "v$it", value = JsonPrimitive(it)) shouldBe VariableEditOutcome.Applied }
            s.setVariable(name = "extra", value = JsonPrimitive(1)) shouldBe VariableEditOutcome.TooMany
            s.setVariable(name = "v0", value = JsonPrimitive(99)) shouldBe VariableEditOutcome.Applied
        }

        test("variables are read-only while scrubbing") {
            val s = state()
            s.sendEvent(eventName = "alt")
            s.scrubTo(0)
            s.setVariable(name = "x", value = JsonPrimitive(1)) shouldBe VariableEditOutcome.RejectedScrubbing
            s.removeVariable("x") shouldBe VariableEditOutcome.RejectedScrubbing
        }

        test("liveVariables follows send, fork and changeGuard") {
            val s = state()
            s.setVariable(name = "x", value = JsonPrimitive(1))
            s.sendEvent(eventName = "alt")
            s.liveVariables["x"] shouldBe JsonPrimitive(1L)
            s.changeGuard(transitionId = "t-go", newOcl = "true") shouldBe PatchOutcome.Applied
            s.liveVariables["x"] shouldBe JsonPrimitive(1L)
        }

        test("forkAtScrubPosition(variables) adopts the given variables") {
            val s = state()
            s.sendEvent(eventName = "alt")
            s.scrubTo(1)
            s.forkAtScrubPosition(variables = mapOf("y" to JsonPrimitive("z")))
            s.liveVariables shouldBe mapOf("y" to JsonPrimitive("z"))
            s.isScrubbing shouldBe false
        }

        test("forkAtScrubPosition() without argument keeps the original behaviour (initial variables)") {
            val s = state()
            s.setVariable(name = "x", value = JsonPrimitive(1))
            s.sendEvent(eventName = "alt")
            s.scrubTo(1)
            s.forkAtScrubPosition()
            s.liveVariables shouldBe s.initialVariables
        }

        // ── resetAll / revertGuard ───────────────────────────────────────────

        test("resetAll restores guards, variables and trace") {
            val s = state()
            s.changeGuard(transitionId = "t-go", newOcl = "true") shouldBe PatchOutcome.Applied
            s.setVariable(name = "x", value = JsonPrimitive(1))
            s.sendEvent(eventName = "go")
            s.resetAll()
            s.model.transitions
                .first { it.id == "t-go" }
                .guard shouldBe "event.amount > 5"
            s.liveVariables shouldBe s.initialVariables
            s.currentHighlightIds() shouldBe setOf("A")
            s.editedGuardIds shouldBe emptySet()
        }

        test("reset() still keeps an edited guard") {
            val s = state()
            s.changeGuard(transitionId = "t-go", newOcl = "true")
            s.reset()
            s.model.transitions
                .first { it.id == "t-go" }
                .guard shouldBe "true"
        }

        test("editedGuardIds tracks edits and treats blank as no guard") {
            val s = state(model = machine(goGuard = null))
            s.editedGuardIds shouldBe emptySet()
            s.changeGuard(transitionId = "t-go", newOcl = "true")
            s.editedGuardIds shouldContain "t-go"
            s.changeGuard(transitionId = "t-go", newOcl = "  ")
            s.editedGuardIds shouldBe emptySet()
        }

        test("revertGuard restores an original guard that the OCL type check would reject") {
            // Original guard written in the AST dialect; changeGuard's OCL check refuses it.
            val original = "vars.a > 1 && vars.b"
            val s = state(model = machine(goGuard = original))
            s.changeGuard(transitionId = "t-go", newOcl = original).shouldBeInstanceOf<PatchOutcome.Rejected>()
            s.changeGuard(transitionId = "t-go", newOcl = "true") shouldBe PatchOutcome.Applied
            s.revertGuard("t-go") shouldBe PatchOutcome.Applied
            s.model.transitions
                .first { it.id == "t-go" }
                .guard shouldBe original
            s.editedGuardIds shouldBe emptySet()
        }

        test("revertGuard keeps the live state") {
            val s = state()
            s.changeGuard(transitionId = "t-go", newOcl = "true")
            s.sendEvent(eventName = "go").shouldBeInstanceOf<StepResult.Transitioned>()
            s.revertGuard("t-go") shouldBe PatchOutcome.Applied
            s.currentHighlightIds() shouldBe setOf("B")
        }

        test("revertGuard needs no confirmation for a protected transition") {
            val protectedModel = buildTrafficLight()
            val s = BehaviourWidgetState(initialModel = protectedModel, runtime = StateMachineRuntime(), editPolicy = EditPolicy.GuardsOnly)
            s.changeGuard(transitionId = "t-yellow-red", newOcl = "true", confirmed = true) shouldBe PatchOutcome.Applied
            s.revertGuard("t-yellow-red") shouldBe PatchOutcome.Applied
            s.model.transitions
                .first { it.id == "t-yellow-red" }
                .guard shouldBe null
        }

        test("revertGuard is rejected without a guard-edit policy, while scrubbing and for unknown ids") {
            val none = state(policy = EditPolicy.None)
            none.revertGuard("t-go").shouldBeInstanceOf<PatchOutcome.Rejected>()
            val s = state()
            s.sendEvent(eventName = "alt")
            s.scrubTo(0)
            s.revertGuard("t-go").shouldBeInstanceOf<PatchOutcome.Rejected>()
            s.scrubTo(s.trace.size)
            s.revertGuard("nope").shouldBeInstanceOf<PatchOutcome.Rejected>()
        }

        test("a guard longer than MAX_GUARD_LENGTH is rejected") {
            val s = state()
            val tooLong = "true" + " ".repeat(MAX_GUARD_LENGTH)
            s.changeGuard(transitionId = "t-go", newOcl = tooLong).shouldBeInstanceOf<PatchOutcome.Rejected>()
            s.editedGuardIds shouldBe emptySet()
        }
    })
