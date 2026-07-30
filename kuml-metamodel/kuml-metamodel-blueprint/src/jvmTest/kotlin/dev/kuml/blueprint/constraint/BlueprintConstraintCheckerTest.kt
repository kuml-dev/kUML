package dev.kuml.blueprint.constraint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.blueprint.model.StepConnection
import dev.kuml.blueprint.model.Touchpoint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class BlueprintConstraintCheckerTest :
    StringSpec({
        val checker = BlueprintConstraintChecker()

        fun valid(): BlueprintModel =
            blueprint(name = "Valid") {
                val a = actor(name = "Kunde", role = ActorRole.CUSTOMER)
                val web = channel(name = "Web", kind = ChannelKind.WEB)
                val tp = touchpoint(name = "T", channel = web)
                phase(name = "P1") {
                    customer(name = "S1", sentiment = Sentiment.NEUTRAL, touchpoints = listOf(tp))
                    step(name = "S1b", actor = a)
                }
                phase(name = "P2") {
                    customer(name = "S2", sentiment = Sentiment.POSITIVE)
                }
                journeyDiagram(name = "J")
            }

        fun errors(m: BlueprintModel) = checker.check(m).filter { it.severity == ViolationSeverity.ERROR }

        fun warnings(m: BlueprintModel) = checker.check(m).filter { it.severity == ViolationSeverity.WARNING }

        "valid model has no violations" {
            checker.check(valid()).shouldBeEmpty()
        }

        // ── ≥1 actor / phase / step ──
        "no actors → error" {
            val m =
                BlueprintModel(
                    name = "x",
                    phases = listOf(Phase(id = "p", name = "P", order = 0)),
                    steps = listOf(JourneyStep(id = "s", name = "S", phaseRef = "p")),
                )
            errors(m).any { it.message.contains("no actors") }.shouldBeTrue()
        }
        "no phases → error" {
            val m = valid().copy(phases = emptyList(), steps = emptyList())
            errors(m).any { it.message.contains("no phases") }.shouldBeTrue()
        }
        "no steps → error" {
            val m = valid().copy(steps = emptyList())
            errors(m).any { it.message.contains("no steps") }.shouldBeTrue()
        }

        // ── empty phase warning ──
        "phase with no step → warning" {
            val base = valid()
            val m = base.copy(phases = base.phases + Phase(id = "pEmpty", name = "Empty", order = base.phases.size))
            warnings(m).any { it.message.contains("empty column") }.shouldBeTrue()
        }

        // ── order contiguity ──
        "non-contiguous phase order → error" {
            val base = valid()
            val m = base.copy(phases = listOf(base.phases[0].copy(order = 0), base.phases[1].copy(order = 5)))
            errors(m).any { it.message.contains("contiguous") }.shouldBeTrue()
        }

        // ── ref resolution ──
        "dangling phaseRef → error" {
            val base = valid()
            val m = base.copy(steps = base.steps + JourneyStep(id = "sx", name = "Sx", phaseRef = "nope"))
            errors(m).any { it.message.contains("phaseRef 'nope'") }.shouldBeTrue()
        }
        "dangling actorRef → error" {
            val base = valid()
            val m = base.copy(steps = base.steps + JourneyStep(id = "sa", name = "Sa", phaseRef = base.phases[0].id, actorRef = "ghost"))
            errors(m).any { it.message.contains("actorRef 'ghost'") }.shouldBeTrue()
        }
        "dangling touchpointRef → error" {
            val base = valid()
            val m =
                base.copy(
                    steps =
                        base.steps + JourneyStep(id = "st", name = "St", phaseRef = base.phases[0].id, touchpointRefs = listOf("nope")),
                )
            errors(m).any { it.message.contains("touchpointRef 'nope'") }.shouldBeTrue()
        }
        "dangling channelRef → error" {
            val base = valid()
            val m = base.copy(touchpoints = base.touchpoints + Touchpoint(id = "tpx", name = "TPx", channelRef = "nope"))
            errors(m).any { it.message.contains("channelRef 'nope'") }.shouldBeTrue()
        }
        "dangling connection endpoint → error" {
            val base = valid()
            val m = base.copy(connections = listOf(StepConnection(id = "c", name = null, sourceRef = "ghostSrc", targetRef = "ghostDst")))
            errors(m).count { it.message.contains("not a step or touchpoint") } shouldBe 2
        }

        // ── sentiment placement ──
        "sentiment outside customer layer → warning" {
            val base = valid()
            val extra =
                JourneyStep(
                    id = "sb",
                    name = "Sb",
                    phaseRef = base.phases[0].id,
                    layer = BlueprintLayer.BACKSTAGE,
                    sentiment = Sentiment.POSITIVE,
                )
            val m = base.copy(steps = base.steps + extra)
            warnings(m).any { it.message.contains("not in the Customer-Actions layer") }.shouldBeTrue()
        }

        // ── emotion curve coverage ──
        "emotion curve requested but no customer sentiment → warning" {
            val m =
                blueprint(name = "NoSentiment") {
                    actor(name = "A")
                    phase(name = "P") { step(name = "S") }
                    journeyDiagram(name = "J", emotionCurve = true)
                }
            warnings(m).any { it.message.contains("emotion curve") }.shouldBeTrue()
        }
    })
