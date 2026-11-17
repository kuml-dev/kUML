package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.BlueprintLayer
import dev.kuml.blueprint.model.BlueprintModel
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.core.model.note.NotePlacement
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.layout.bridge.note.NotePlacementMissCollector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Nacharbeit 101: Service Blueprint notes honour the placement hint against their first anchor card. */
class BlueprintNotePlacementHintTest :
    StringSpec({
        // Three stacked customer cards in one cell; the note is anchored at the middle one.
        fun model(
            placement: NotePlacement?,
            anchorFirst: Boolean = false,
        ): BlueprintModel =
            blueprint(name = "Service") {
                lateinit var first: String
                lateinit var middle: String
                phase(name = "Request") {
                    first = customer(name = "Reads", sentiment = Sentiment.NEUTRAL)
                    middle = customer(name = "Fills in", sentiment = Sentiment.NEUTRAL)
                    customer(name = "Sends", sentiment = Sentiment.NEUTRAL)
                }
                note(text = "About step", if (anchorFirst) first else middle, id = "n1", placement = placement)
                blueprintDiagram(name = "Blueprint", emotionCurve = false)
            }

        fun geometry(m: BlueprintModel) =
            BlueprintGeometry(model = m, visibleLayers = BlueprintLayer.entries.toSet(), showEmotionCurve = false)

        fun anchorCard(
            g: BlueprintGeometry,
            m: BlueprintModel,
        ) = g.cardRect(m.noteLinks.single().targetId)!!

        "ABOVE puts the note entirely above the anchor card, BELOW entirely below" {
            val (above, aboveMisses) = NotePlacementMissCollector.collecting { geometry(model(NotePlacement.ABOVE)) }
            val ma = model(NotePlacement.ABOVE)
            val note = above.placedNotes.single()
            (note.y + note.h <= anchorCard(above, ma).y) shouldBe true
            aboveMisses shouldBe emptyList()

            val (below, belowMisses) = NotePlacementMissCollector.collecting { geometry(model(NotePlacement.BELOW)) }
            val mb = model(NotePlacement.BELOW)
            val nb = below.placedNotes.single()
            (nb.y >= anchorCard(below, mb).let { it.y + it.h }) shouldBe true
            belowMisses shouldBe emptyList()
        }

        "RIGHT, NEAR and no hint give the default placement without a miss" {
            val (plain, _) = NotePlacementMissCollector.collecting { geometry(model(null)) }
            for (p in listOf(NotePlacement.RIGHT, NotePlacement.NEAR)) {
                val (g, misses) = NotePlacementMissCollector.collecting { geometry(model(p)) }
                g.placedNotes.single().y shouldBe plain.placedNotes.single().y
                misses shouldBe emptyList()
            }
        }

        "LEFT can never be honoured: default placement and one miss that wanted LEFT" {
            val (plain, _) = NotePlacementMissCollector.collecting { geometry(model(null)) }
            val (g, misses) = NotePlacementMissCollector.collecting { geometry(model(NotePlacement.LEFT)) }
            g.placedNotes.single().y shouldBe plain.placedNotes.single().y
            misses.size shouldBe 1
            misses.single().noteId shouldBe "n1"
            misses.single().wanted shouldBe NotePlacement.LEFT
            misses.single().placed shouldBe NotePlacement.RIGHT
        }

        "ABOVE the first card of the grid has no room: the note is pushed down and the miss is reported" {
            val (g, misses) = NotePlacementMissCollector.collecting { geometry(model(NotePlacement.ABOVE, anchorFirst = true)) }
            val m = model(NotePlacement.ABOVE, anchorFirst = true)
            val card = anchorCard(g, m)
            val note = g.placedNotes.single()
            (note.y + note.h > card.y) shouldBe true
            misses.size shouldBe 1
            misses.single().wanted shouldBe NotePlacement.ABOVE
            (misses.single().placed == NotePlacement.ABOVE) shouldBe false
        }

        "a note without a hint and a NEAR note render the same SVG; a side hint changes it" {
            fun svg(p: NotePlacement?): String {
                val m = model(p)
                return KumlSvgRenderer.toSvg(model = m, diagram = m.diagrams.first() as dev.kuml.blueprint.model.BlueprintDiagramFull)
            }
            svg(NotePlacement.NEAR) shouldBe svg(null)
            (svg(NotePlacement.BELOW) == svg(null)) shouldBe false
        }

        "a free-standing note ignores the hint without a miss" {
            val m =
                blueprint(name = "Service") {
                    phase(name = "Request") { customer(name = "Reads", sentiment = Sentiment.NEUTRAL) }
                    note(text = "Free", id = "n1", placement = NotePlacement.ABOVE)
                    blueprintDiagram(name = "Blueprint", emotionCurve = false)
                }
            val (_, misses) = NotePlacementMissCollector.collecting { geometry(m) }
            misses shouldBe emptyList()
        }
    })
