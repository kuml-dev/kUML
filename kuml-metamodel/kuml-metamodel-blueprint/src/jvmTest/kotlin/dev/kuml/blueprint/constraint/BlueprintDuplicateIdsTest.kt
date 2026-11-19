package dev.kuml.blueprint.constraint

import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BlueprintDuplicateIdsTest :
    StringSpec({
        fun model(vararg stepIds: String): BlueprintModel =
            BlueprintModel(
                name = "M",
                phases = listOf(Phase(id = "p", name = "P", order = 0)),
                steps = stepIds.map { JourneyStep(id = it, name = "Step $it", phaseRef = "p") },
            )

        "two steps with the same id give one entry with count 2" {
            BlueprintDuplicateIds.duplicateStepIds(model("a", "b", "a")) shouldBe listOf(DuplicateStepId(id = "a", count = 2))
        }
        "three steps with the same id give one entry with count 3" {
            BlueprintDuplicateIds.duplicateStepIds(model("a", "a", "a")) shouldBe listOf(DuplicateStepId(id = "a", count = 3))
        }
        "two duplicated ids come in order of first occurrence" {
            BlueprintDuplicateIds.duplicateStepIds(model("x", "y", "y", "x", "z")) shouldBe
                listOf(DuplicateStepId(id = "x", count = 2), DuplicateStepId(id = "y", count = 2))
        }
        "a clean model has no duplicates" {
            BlueprintDuplicateIds.duplicateStepIds(model("a", "b", "c")).shouldBeEmpty()
        }
        "a model without steps has no duplicates" {
            BlueprintDuplicateIds.duplicateStepIds(model()).shouldBeEmpty()
        }
        "the message names id and count but not the step name" {
            val m = DuplicateStepId(id = "a", count = 2).message()
            m shouldContain "a"
            m shouldContain "2 occurrences"
            m shouldNotContain "Step"
        }
        "a step id shared with a phase is not flagged" {
            BlueprintDuplicateIds.duplicateStepIds(model("p", "q")).shouldBeEmpty()
        }
        "the code is KUML-BP-W001" {
            BlueprintCodes.DUPLICATE_STEP_ID shouldBe "KUML-BP-W001"
        }
    })
