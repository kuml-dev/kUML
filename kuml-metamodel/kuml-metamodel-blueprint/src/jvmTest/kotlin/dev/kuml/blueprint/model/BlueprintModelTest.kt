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
            val web = Channel("channel_0", "Website", ChannelKind.WEB)
            val tp = Touchpoint("tp_0", "Banner", channelRef = web.id, symbol = TouchpointSymbol.CIRCLE)
            val actor = Actor("actor_0", "Kunde", ActorRole.CUSTOMER)
            val p0 = Phase("phase_0", "Entdeckung", order = 0)
            val p1 = Phase("phase_1", "Kauf", order = 1)
            val s0 =
                JourneyStep("step_0", "Sieht Banner", p0.id, BlueprintLayer.CUSTOMER_ACTIONS, listOf(tp.id), actor.id, Sentiment.NEUTRAL)
            val s1 = JourneyStep("step_1", "Kauft", p1.id, BlueprintLayer.CUSTOMER_ACTIONS, sentiment = Sentiment.POSITIVE)
            val s2 = JourneyStep("step_2", "Backoffice", p1.id, BlueprintLayer.BACKSTAGE)
            val conn = StepConnection("conn_0", null, s1.id, s2.id, ConnectionStyle.DASHED)
            return BlueprintModel(
                name = "Test",
                actors = listOf(actor),
                channels = listOf(web),
                touchpoints = listOf(tp),
                phases = listOf(p1, p0),
                steps = listOf(s0, s1, s2),
                connections = listOf(conn),
                diagrams = listOf(JourneyDiagram("J"), BlueprintDiagramFull("B")),
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
                    Actor("a", "n", ActorRole.PARTNER, "desc"),
                    Channel("c", "n", ChannelKind.PHONE),
                    Touchpoint("t", "n", "c", TouchpointSymbol.HEXAGON),
                    Phase("p", "n", 3),
                    JourneyStep("s", "n", "p", BlueprintLayer.SUPPORT_PROCESSES, listOf("t"), "a", Sentiment.NEGATIVE, "pain", "chance"),
                    StepConnection("x", "n", "s", "s2", ConnectionStyle.DASHED),
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
            m.stepsIn("phase_1", BlueprintLayer.CUSTOMER_ACTIONS).map { it.id } shouldBe listOf("step_1")
            m.stepsIn("phase_1", BlueprintLayer.BACKSTAGE).map { it.id } shouldBe listOf("step_2")
            m.stepsIn("phase_0", BlueprintLayer.BACKSTAGE) shouldBe emptyList()
        }

        "activeLayers reflects occupied layers" {
            sampleModel().activeLayers() shouldBe setOf(BlueprintLayer.CUSTOMER_ACTIONS, BlueprintLayer.BACKSTAGE)
        }

        "orderedPhases sorts by order deterministically" {
            sampleModel().orderedPhases().map { it.id } shouldBe listOf("phase_0", "phase_1")
        }

        "emotionCurve averages customer sentiments, empty phase null" {
            val p = Phase("phase_0", "P", 0)
            val m =
                BlueprintModel(
                    name = "E",
                    phases = listOf(p),
                    steps =
                        listOf(
                            JourneyStep("s0", "a", p.id, sentiment = Sentiment.POSITIVE),
                            JourneyStep("s1", "b", p.id, sentiment = Sentiment.VERY_POSITIVE),
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
            val p = Phase("phase_b", "B", 0)
            // NEUTRAL(0) + NEGATIVE(-1) → average = -0.5 → Math.round(-0.5) = 0 → NEUTRAL
            val m =
                BlueprintModel(
                    name = "Boundary",
                    phases = listOf(p),
                    steps =
                        listOf(
                            JourneyStep("b0", "x", p.id, sentiment = Sentiment.NEUTRAL),
                            JourneyStep("b1", "y", p.id, sentiment = Sentiment.NEGATIVE),
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
            JourneyDiagram("j").visibleLayers shouldBe setOf(BlueprintLayer.CUSTOMER_ACTIONS)
            BlueprintDiagramFull("b").visibleLayers shouldBe BlueprintLayer.entries.toSet()
            BlueprintDiagramFull("b").showLines shouldBe BlueprintLine.entries.toSet()
        }
    })
