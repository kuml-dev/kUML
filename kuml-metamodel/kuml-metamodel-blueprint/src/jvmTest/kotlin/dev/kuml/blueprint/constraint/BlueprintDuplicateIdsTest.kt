package dev.kuml.blueprint.constraint

import dev.kuml.blueprint.model.Actor
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.Channel
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.StepConnection
import dev.kuml.blueprint.model.Touchpoint
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

        fun kinds(
            actors: List<String> = emptyList(),
            phases: List<String> = emptyList(),
            steps: List<String> = emptyList(),
            channels: List<String> = emptyList(),
            touchpoints: List<String> = emptyList(),
            connections: List<String> = emptyList(),
        ): BlueprintModel =
            BlueprintModel(
                name = "M",
                actors = actors.map { Actor(id = it, name = "Actor $it") },
                channels = channels.map { Channel(id = it, name = "Channel $it") },
                touchpoints = touchpoints.map { Touchpoint(id = it, name = "Touchpoint $it") },
                phases = phases.mapIndexed { i, id -> Phase(id = id, name = "Phase $id", order = i) },
                steps = steps.map { JourneyStep(id = it, name = "Step $it", phaseRef = "p") },
                connections = connections.map { StepConnection(id = it, sourceRef = "a", targetRef = "b") },
            )

        "a step id equal to a phase id is one cross-kind entry" {
            BlueprintDuplicateIds.crossKindIds(kinds(phases = listOf("x"), steps = listOf("x"))) shouldBe
                listOf(CrossKindId(id = "x", kinds = listOf("phase", "step")))
        }
        "a step id equal to an actor id is one cross-kind entry" {
            BlueprintDuplicateIds.crossKindIds(kinds(actors = listOf("x"), steps = listOf("x"))) shouldBe
                listOf(CrossKindId(id = "x", kinds = listOf("actor", "step")))
        }
        "one id in three kinds is a single entry in list order" {
            BlueprintDuplicateIds.crossKindIds(kinds(steps = listOf("x"), phases = listOf("x"), actors = listOf("x"))) shouldBe
                listOf(CrossKindId(id = "x", kinds = listOf("actor", "phase", "step")))
        }
        "channel, touchpoint and connection kinds are compared too" {
            BlueprintDuplicateIds.crossKindIds(
                kinds(channels = listOf("c"), touchpoints = listOf("c"), connections = listOf("c")),
            ) shouldBe listOf(CrossKindId(id = "c", kinds = listOf("channel", "touchpoint", "connection")))
        }
        "no collision gives no entry" {
            BlueprintDuplicateIds.crossKindIds(kinds(actors = listOf("a"), phases = listOf("p"), steps = listOf("s"))).shouldBeEmpty()
        }
        "a duplicate within one kind is W001 only" {
            val m = kinds(steps = listOf("a", "a"))
            BlueprintDuplicateIds.crossKindIds(m).shouldBeEmpty()
            BlueprintDuplicateIds.duplicateStepIds(m) shouldBe listOf(DuplicateStepId(id = "a", count = 2))
        }
        "an in-kind duplicate plus a cross-kind collision reports both independently" {
            val m = kinds(steps = listOf("x", "x"), phases = listOf("x"))
            BlueprintDuplicateIds.crossKindIds(m) shouldBe listOf(CrossKindId(id = "x", kinds = listOf("phase", "step")))
            BlueprintDuplicateIds.duplicateStepIds(m) shouldBe listOf(DuplicateStepId(id = "x", count = 2))
        }
        "blank ids are ignored" {
            BlueprintDuplicateIds
                .crossKindIds(
                    kinds(actors = listOf("", " "), phases = listOf("", " "), steps = listOf(" ")),
                ).shouldBeEmpty()
        }
        "the cross-kind message names id and kinds but no title" {
            val e = BlueprintDuplicateIds.crossKindIds(kinds(phases = listOf("x"), steps = listOf("x"))).single()
            e.message() shouldContain "x"
            e.message() shouldContain "phase, step"
            e.message() shouldNotContain "Step x"
            e.message() shouldNotContain "Phase x"
        }
        "the code is KUML-BP-W002" {
            BlueprintCodes.CROSS_KIND_ID shouldBe "KUML-BP-W002"
        }
    })
