package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.JourneyStep
import dev.kuml.blueprint.model.Phase
import dev.kuml.blueprint.model.StepConnection
import dev.kuml.core.model.note.KumlNote
import dev.kuml.core.model.note.KumlNoteLink
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Nacharbeit 122c: duplicate step ids (JSON/OKF imports, `KUML-BP-W001`) are all drawn as their own card; references bind to the
 * first step in model order. Structural and font independent (geometry numbers, element ids), no byte comparison of text output.
 */
class BlueprintDuplicateStepCardsTest :
    StringSpec({
        val allLayers = BlueprintLayer.entries.toSet()

        fun step(
            id: String,
            phase: String = "p1",
            layer: BlueprintLayer = BlueprintLayer.CUSTOMER_ACTIONS,
        ) = JourneyStep(id = id, name = "Step $id", phaseRef = phase, layer = layer)

        fun model(
            steps: List<JourneyStep>,
            connections: List<StepConnection> = emptyList(),
            notes: List<KumlNote> = emptyList(),
            links: List<KumlNoteLink> = emptyList(),
        ) = BlueprintModel(
            name = "M",
            phases = listOf(Phase(id = "p1", name = "One", order = 0), Phase(id = "p2", name = "Two", order = 1)),
            steps = steps,
            connections = connections,
            notes = notes,
            noteLinks = links,
            diagrams = listOf(BlueprintDiagramFull(name = "B")),
        )

        fun geo(
            m: BlueprintModel,
            layers: Set<BlueprintLayer> = allLayers,
        ) = BlueprintGeometry(model = m, visibleLayers = layers, showEmotionCurve = false)

        fun svg(m: BlueprintModel): String = KumlSvgRenderer.toSvg(model = m, diagram = m.diagrams.first())

        fun ids(svg: String): List<String> = Regex("""\bid="([^"]*)"""").findAll(svg).map { it.groupValues[1] }.toList()

        "two steps with one id in the same cell get two stacked cards" {
            val g = geo(model(listOf(step("s1"), step("s1"))))
            val cards = g.cardsIn(phaseId = "p1", layer = BlueprintLayer.CUSTOMER_ACTIONS)
            cards.size shouldBe 2
            cards.map { it.stepIndex } shouldBe listOf(0, 1)
            (cards[0].y + cards[0].h <= cards[1].y) shouldBe true
        }

        "duplicates in different cells each sit in their own cell" {
            val g = geo(model(listOf(step("s1"), step("s1", phase = "p2", layer = BlueprintLayer.BACKSTAGE))))
            val a = g.cardsIn(phaseId = "p1", layer = BlueprintLayer.CUSTOMER_ACTIONS).single()
            val b = g.cardsIn(phaseId = "p2", layer = BlueprintLayer.BACKSTAGE).single()
            (a.x >= g.contentLeft && a.x + a.w <= g.contentLeft + g.columnWidth) shouldBe true
            (b.x >= g.contentLeft + g.columnWidth && b.x + b.w <= g.contentLeft + 2 * g.columnWidth) shouldBe true
            (b.y > a.y) shouldBe true
        }

        "a connection to a duplicated id ends at the first card" {
            val m =
                model(
                    steps = listOf(step("s0"), step("s1"), step("s1", phase = "p2")),
                    connections = listOf(StepConnection(id = "c1", sourceRef = "s0", targetRef = "s1")),
                )
            val g = geo(m)
            val first = g.cardsIn(phaseId = "p1", layer = BlueprintLayer.CUSTOMER_ACTIONS).first { it.stepIndex == 1 }
            g.cardRect("s1")!!.stepIndex shouldBe first.stepIndex
            g.cardRect("s1")!!.cx shouldBe first.cx
            g.cardRect("s1")!!.cy shouldBe first.cy
            cardCenterOf(model = m, geo = g, elementId = "s1") shouldBe (first.cx to first.cy)
        }

        "a note anchored to a duplicated id attaches to the first card" {
            val m =
                model(
                    steps = listOf(step("s1"), step("s1", phase = "p2")),
                    notes = listOf(KumlNote(id = "n1", body = "Note")),
                    links = listOf(KumlNoteLink(id = "l1", noteId = "n1", targetId = "s1")),
                )
            val g = geo(m)
            val first = g.cardRect("s1")!!
            first.stepIndex shouldBe 0
            val end =
                g.placedNotes
                    .single()
                    .routes
                    .single()
                    .second
                    .last()
            // the route ends on the east edge of the first card (column 1), never at the second duplicate (column 2)
            (end.first <= first.x + first.w + 0.5) shouldBe true
        }

        "element ids: first card keeps the id, later copies get dup suffixes, no id twice" {
            val s = svg(model(listOf(step("s1"), step("s1", phase = "p2"), step("s1", phase = "p2", layer = BlueprintLayer.BACKSTAGE))))
            s shouldContain """id="s1""""
            s shouldContain """id="s1.dup2""""
            s shouldContain """id="s1.dup3""""
            s shouldNotContain "s1.dup1"
            val all = ids(s)
            all.size shouldBe all.toSet().size
        }

        "a hidden first occurrence leaves the id unbound, the later duplicate is not used" {
            val m = model(listOf(step("s1", layer = BlueprintLayer.BACKSTAGE), step("s1")))
            val g = geo(m, layers = setOf(BlueprintLayer.CUSTOMER_ACTIONS))
            g.cardsIn(phaseId = "p1", layer = BlueprintLayer.CUSTOMER_ACTIONS).size shouldBe 1
            g.cardRect("s1") shouldBe null
        }

        "valid models keep model order and the id lookup" {
            val m = model(listOf(step("a"), step("b"), step("c", phase = "p2")))
            val g = geo(m)
            g.cardsIn(phaseId = "p1", layer = BlueprintLayer.CUSTOMER_ACTIONS).map { it.step.id } shouldBe listOf("a", "b")
            listOf("a", "b", "c").forEach { id -> g.cardRect(id)!!.step.id shouldBe id }
            val s = svg(m)
            ids(s).filter { it in setOf("a", "b", "c") } shouldBe listOf("a", "b", "c")
            s shouldNotContain ".dup"
        }

        "data-class-equal steps still get two cards" {
            val same = step("s1")
            val g = geo(model(listOf(same, same)))
            g.cardsIn(phaseId = "p1", layer = BlueprintLayer.CUSTOMER_ACTIONS).map { it.stepIndex } shouldBe listOf(0, 1)
        }

        "sample output with same-cell and cross-cell duplicates, a connection and a note" {
            val m =
                model(
                    steps =
                        listOf(
                            step("s0"),
                            step("s1"),
                            step("s1"),
                            step("s1", phase = "p2", layer = BlueprintLayer.BACKSTAGE),
                        ),
                    connections = listOf(StepConnection(id = "c1", sourceRef = "s0", targetRef = "s1")),
                    notes = listOf(KumlNote(id = "n1", body = "Anchored to the duplicated id")),
                    links = listOf(KumlNoteLink(id = "l1", noteId = "n1", targetId = "s1")),
                )
            SampleOutput.write(filename = "blueprint/duplicate-step-ids.svg", content = svg(m))
        }
    })
