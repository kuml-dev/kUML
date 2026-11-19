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
 * Nacharbeit 111: duplicate Service Blueprint step ids are `KUML-BP-W001`. The DSL cannot express duplicates (they come from
 * JSON and OKF imports), so the extracted diagram is built directly. `render` consumes the same [NoteChecks.of] result as
 * `validate`, so asserting it here covers both paths; the render itself is not changed.
 */
class BlueprintDuplicateStepIdCliTest :
    FunSpec({
        fun extracted(stepIds: List<String>): ExtractedDiagram.Blueprint {
            val model =
                BlueprintModel(
                    name = "M",
                    phases = listOf(Phase(id = "p", name = "P", order = 0)),
                    steps = stepIds.map { JourneyStep(id = it, name = "SECRET-NAME", phaseRef = "p") },
                )
            return ExtractedDiagram.Blueprint(model = model, diagram = JourneyDiagram(name = "J"))
        }

        test("a duplicated step id is one warning KUML-BP-W001 naming the id, once") {
            val findings = ValidationService.findingsOf(extracted(listOf("s1", "s2", "s1", "s1")))
            val f = findings.filter { it.code == "KUML-BP-W001" }
            f shouldHaveSize 1
            f.single().severity shouldBe Severity.WARNING
            f.single().message shouldContain "s1"
            f.single().message shouldContain "3 occurrences"
            f.single().message shouldNotContain "SECRET-NAME"
        }

        test("NoteChecks.of reports it as a non-error finding") {
            val f = NoteChecks.of(extracted(listOf("a", "a"))).single { it.code == "KUML-BP-W001" }
            f.isError shouldBe false
            f.subjectId shouldBe "a"
        }

        test("a clean model has no blueprint finding") {
            ValidationService.findingsOf(extracted(listOf("a", "b"))).filter { it.code.startsWith("KUML-BP") }.shouldBeEmpty()
        }

        test("a script-built blueprint has no blueprint finding") {
            val script = this::class.java.getResource("/minimal-blueprint.kuml.kts")!!.readText()
            ValidationService.validate(script = script).filter { it.code.startsWith("KUML-BP") }.shouldBeEmpty()
        }
    })
