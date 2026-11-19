package dev.kuml.cli

import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyDiagram
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import dev.kuml.core.script.ExtractedDiagram
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 121d: an id used by several Service Blueprint element kinds is `KUML-BP-W002` (warning only). The DSL cannot
 * produce collisions, so the extracted diagram is built directly. `--strict` only promotes specific checker findings,
 * never note findings, so a warning stays a warning (asserted via the severity and the non-error flag).
 */
class BlueprintCrossKindIdCliTest :
    FunSpec({
        fun extracted(
            phaseId: String,
            stepId: String,
        ): ExtractedDiagram.Blueprint {
            val model =
                BlueprintModel(
                    name = "M",
                    phases = listOf(Phase(id = phaseId, name = "SECRET-PHASE", order = 0)),
                    steps = listOf(JourneyStep(id = stepId, name = "SECRET-NAME", phaseRef = phaseId)),
                )
            return ExtractedDiagram.Blueprint(model = model, diagram = JourneyDiagram(name = "J"))
        }

        test("a phase and a step sharing an id are one warning KUML-BP-W002 naming id and kinds") {
            val f = ValidationService.findingsOf(extracted(phaseId = "p", stepId = "p")).filter { it.code == "KUML-BP-W002" }
            f shouldHaveSize 1
            f.single().severity shouldBe Severity.WARNING
            f.single().message shouldContain "p"
            f.single().message shouldContain "phase"
            f.single().message shouldContain "step"
            f.single().message shouldNotContain "SECRET"
        }

        test("NoteChecks.of reports it as a non-error finding on the id") {
            val f = NoteChecks.of(extracted(phaseId = "p", stepId = "p")).single { it.code == "KUML-BP-W002" }
            f.isError shouldBe false
            f.subjectId shouldBe "p"
        }

        test("a clean model has no blueprint finding") {
            ValidationService.findingsOf(extracted(phaseId = "p", stepId = "s")).filter { it.code.startsWith("KUML-BP") }.shouldBeEmpty()
        }

        test("a script-built blueprint has no blueprint finding") {
            val script = this::class.java.getResource("/minimal-blueprint.kuml.kts")!!.readText()
            ValidationService.validate(script = script).filter { it.code.startsWith("KUML-BP") }.shouldBeEmpty()
        }
    })
