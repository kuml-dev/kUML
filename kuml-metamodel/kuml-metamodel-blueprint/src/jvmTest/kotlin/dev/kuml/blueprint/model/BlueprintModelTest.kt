package dev.kuml.blueprint.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Core-metamodel tests for V3.1.21: construction, serialization roundtrip,
 * lookup, layer filtering, emotion-curve averaging and sentiment clamping.
 */
class BlueprintModelTest :
    StringSpec({
        val json = Json { prettyPrint = false }

        fun sampleModel(): BlueprintModel {
            val web = Channel(id = "channel_0", name = "Website", kind = ChannelKind.WEB)
            val tp = Touchpoint(id = "tp_0", name = "Banner", channelRef = web.id, symbol = TouchpointSymbol.CIRCLE)
            val actor = Actor(id = "actor_0", name = "Kunde", role = ActorRole.CUSTOMER)
            val p0 = Phase(id = "phase_0", name = "Entdeckung", order = 0)
            val p1 = Phase(id = "phase_1", name = "Kauf", order = 1)
            val s0 =
                JourneyStep(
                    id = "step_0",
                    name = "Sieht Banner",
                    phaseRef = p0.id,
                    layer = BlueprintLayer.CUSTOMER_ACTIONS,
                    touchpointRefs = listOf(tp.id),
                    actorRef = actor.id,
                    sentiment = Sentiment.NEUTRAL,
                )
            val s1 =
                JourneyStep(
                    id = "step_1",
                    name = "Kauft",
                    phaseRef = p1.id,
                    layer = BlueprintLayer.CUSTOMER_ACTIONS,
                    sentiment = Sentiment.POSITIVE,
                )
            val s2 = JourneyStep(id = "step_2", name = "Backoffice", phaseRef = p1.id, layer = BlueprintLayer.BACKSTAGE)
            val conn = StepConnection(id = "conn_0", name = null, sourceRef = s1.id, targetRef = s2.id, style = ConnectionStyle.DASHED)
            return BlueprintModel(
                name = "Test",
                actors = listOf(actor),
                channels = listOf(web),
                touchpoints = listOf(tp),
                phases = listOf(p1, p0),
                steps = listOf(s0, s1, s2),
                connections = listOf(conn),
                diagrams = listOf(JourneyDiagram(name = "J"), BlueprintDiagramFull(name = "B")),
            )
        }

        "model serialization roundtrip is lossless" {
            val m = sampleModel()
            val back = json.decodeFromString(BlueprintModel.serializer(), json.encodeToString(BlueprintModel.serializer(), m))
            back shouldBe m
        }

        "each element variant roundtrips" {
            val elements: List<BlueprintElement> =
                listOf(
                    Actor(id = "a", name = "n", role = ActorRole.PARTNER, description = "desc"),
                    Channel(id = "c", name = "n", kind = ChannelKind.PHONE),
                    Touchpoint(id = "t", name = "n", channelRef = "c", symbol = TouchpointSymbol.HEXAGON),
                    Phase(id = "p", name = "n", order = 3),
                    JourneyStep(
                        id = "s",
                        name = "n",
                        phaseRef = "p",
                        layer = BlueprintLayer.SUPPORT_PROCESSES,
                        touchpointRefs = listOf("t"),
                        actorRef = "a",
                        sentiment = Sentiment.NEGATIVE,
                        painPoint = "pain",
                        opportunity = "chance",
                    ),
                    StepConnection(id = "x", name = "n", sourceRef = "s", targetRef = "s2", style = ConnectionStyle.DASHED),
                )
            elements.forEach { e ->
                val back = json.decodeFromString(BlueprintElement.serializer(), json.encodeToString(BlueprintElement.serializer(), e))
                back shouldBe e
            }
        }

        "elementById resolves across all lists" {
            val m = sampleModel()
            m.elementById("actor_0")!!.name shouldBe "Kunde"
            m.elementById("channel_0")!!.name shouldBe "Website"
            m.elementById("tp_0")!!.name shouldBe "Banner"
            m.elementById("phase_0")!!.name shouldBe "Entdeckung"
            m.elementById("step_0")!!.name shouldBe "Sieht Banner"
            m.elementById("conn_0")!!.id shouldBe "conn_0"
            m.elementById("nope").shouldBeNull()
        }

        "stepsIn filters by phase and layer" {
            val m = sampleModel()
            m.stepsIn(phaseId = "phase_1", layer = BlueprintLayer.CUSTOMER_ACTIONS).map { it.id } shouldBe listOf("step_1")
            m.stepsIn(phaseId = "phase_1", layer = BlueprintLayer.BACKSTAGE).map { it.id } shouldBe listOf("step_2")
            m.stepsIn(phaseId = "phase_0", layer = BlueprintLayer.BACKSTAGE) shouldBe emptyList()
        }

        "activeLayers reflects occupied layers" {
            sampleModel().activeLayers() shouldBe setOf(BlueprintLayer.CUSTOMER_ACTIONS, BlueprintLayer.BACKSTAGE)
        }

        "orderedPhases sorts by order deterministically" {
            sampleModel().orderedPhases().map { it.id } shouldBe listOf("phase_0", "phase_1")
        }

        "emotionCurve averages customer sentiments, empty phase null" {
            val p = Phase(id = "phase_0", name = "P", order = 0)
            val m =
                BlueprintModel(
                    name = "E",
                    phases = listOf(p),
                    steps =
                        listOf(
                            JourneyStep(id = "s0", name = "a", phaseRef = p.id, sentiment = Sentiment.POSITIVE),
                            JourneyStep(id = "s1", name = "b", phaseRef = p.id, sentiment = Sentiment.VERY_POSITIVE),
                        ),
                )
            m.emotionCurve().single().second shouldBe Sentiment.VERY_POSITIVE // (1+2)/2 = 1.5 -> round 2
            val empty = BlueprintModel(name = "E2", phases = listOf(p))
            empty
                .emotionCurve()
                .single()
                .second
                .shouldBeNull()
        }

        // Boundary-case: avg = -0.5 uses Math.round (half-up) → rounds to 0 (NEUTRAL),
        // not -1 (NEGATIVE). This is the expected behaviour — the test pins it explicitly
        // so a future change to the rounding strategy surfaces immediately.
        "emotionCurve boundary: avg=-0.5 rounds to NEUTRAL (half-up, not NEGATIVE)" {
            val p = Phase(id = "phase_b", name = "B", order = 0)
            // NEUTRAL(0) + NEGATIVE(-1) → average = -0.5 → Math.round(-0.5) = 0 → NEUTRAL
            val m =
                BlueprintModel(
                    name = "Boundary",
                    phases = listOf(p),
                    steps =
                        listOf(
                            JourneyStep(id = "b0", name = "x", phaseRef = p.id, sentiment = Sentiment.NEUTRAL),
                            JourneyStep(id = "b1", name = "y", phaseRef = p.id, sentiment = Sentiment.NEGATIVE),
                        ),
                )
            m.emotionCurve().single().second shouldBe Sentiment.NEUTRAL // -0.5 rounds to 0
        }

        "Sentiment.of clamps to [-2..+2]" {
            Sentiment.of(5) shouldBe Sentiment.VERY_POSITIVE
            Sentiment.of(-9) shouldBe Sentiment.VERY_NEGATIVE
            Sentiment.of(0) shouldBe Sentiment.NEUTRAL
            Sentiment.NEGATIVE.value shouldBe -1
        }

        "JourneyDiagram default visibleLayers is Customer only" {
            JourneyDiagram(name = "j").visibleLayers shouldBe setOf(BlueprintLayer.CUSTOMER_ACTIONS)
            BlueprintDiagramFull(name = "b").visibleLayers shouldBe BlueprintLayer.entries.toSet()
            BlueprintDiagramFull(name = "b").showLines shouldBe BlueprintLine.entries.toSet()
        }
    })
