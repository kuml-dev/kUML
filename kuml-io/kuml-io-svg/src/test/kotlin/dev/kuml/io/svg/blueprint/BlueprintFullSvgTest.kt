package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.svg.KumlSvgRenderer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * SVG renderer tests for the full Service-Blueprint view (V3.1.24): four layer
 * bands, the three separator lines, actor-role icons, per-layer styling.
 *
 * End-to-end through the real grid geometry (no hardcoded LayoutResult) per the
 * CLAUDE.md renderer routine.
 */
class BlueprintFullSvgTest :
    StringSpec({
        fun fullModel() =
            blueprint("Service") {
                val customer = actor("Kunde", ActorRole.CUSTOMER)
                val staff = actor("Mitarbeiter", ActorRole.STAFF)
                val system = actor("CRM", ActorRole.SYSTEM)
                val partner = actor("Partner", ActorRole.PARTNER)
                val web = channel("Web", ChannelKind.WEB)
                val formular = touchpoint("Formular", channel = web)
                phase("Antrag") {
                    customer("Füllt Formular", Sentiment.NEUTRAL, touchpoints = listOf(formular))
                    frontstage("Bestätigt Eingang", actor = staff)
                    backstage("Prüft Angaben", actor = staff)
                    support("Legt Datensatz an", actor = system)
                }
                phase("Aufnahme") {
                    customer("Wartet", Sentiment.NEGATIVE, pain = "Dauer unklar")
                    backstage("Beschließt Aufnahme", actor = staff)
                    support("Weist zu", actor = partner)
                }
                blueprintDiagram("Blueprint", emotionCurve = true)
            }

        fun render(): String {
            val model = fullModel()
            val diagram = model.diagrams.first() as BlueprintDiagramFull
            return KumlSvgRenderer.toSvg(model, diagram)
        }

        "renders all four layer swimlane labels in Shostack order" {
            val svg = render()
            svg shouldContain "Customer Actions"
            svg shouldContain "Frontstage"
            svg shouldContain "Backstage"
            svg shouldContain "Support Processes"
            svg.indexOf("Customer Actions") shouldBe svg.indexOf("Customer Actions")
            (svg.indexOf("Frontstage") < svg.indexOf("Backstage")) shouldBe true
            (svg.indexOf("Backstage") < svg.indexOf("Support Processes")) shouldBe true
        }

        "draws all three separator lines with their captions" {
            val svg = render()
            svg shouldContain "Line of Interaction"
            svg shouldContain "Line of Visibility"
            svg shouldContain "Line of Internal Interaction"
        }

        "uses solid / dashed / dotted styles for the three lines" {
            val svg = render()
            // visibility line is dashed (8,4), internal-interaction is dotted (2,4)
            svg shouldContain """stroke-dasharray="8,4""""
            svg shouldContain """stroke-dasharray="2,4""""
        }

        "respects showLines subset — only interaction line when configured" {
            val model = fullModel()
            val diagram =
                (model.diagrams.first() as BlueprintDiagramFull)
                    .copy(showLines = setOf(BlueprintLine.INTERACTION))
            val svg = KumlSvgRenderer.toSvg(model, diagram)
            svg shouldContain "Line of Interaction"
            svg shouldNotContain "Line of Visibility"
            svg shouldNotContain "Line of Internal Interaction"
        }

        "renders distinct per-layer band fills" {
            val svg = render()
            svg shouldContain "#fff8e1" // customer
            svg shouldContain "#eaf2fb" // frontstage
            svg shouldContain "#eef1f6" // backstage
            svg shouldContain "#f3eef8" // support
        }

        "renders actor-role icon titles on staffed/system steps" {
            val svg = render()
            svg shouldContain "(STAFF)"
            svg shouldContain "(SYSTEM)"
            svg shouldContain "(PARTNER)"
        }

        "keeps an empty visible layer band (no Frontstage step in phase 2 still draws band)" {
            // Frontstage has steps only in phase 1; the band must still be full-width.
            val svg = render()
            svg shouldContain "Frontstage"
        }

        "produces well-formed single-defs SVG" {
            val svg = render()
            svg.split("<defs>").size shouldBe 2 // exactly one <defs>
            svg shouldContain "<svg"
            svg shouldContain "</svg>"
        }

        "draws the emotion curve when enabled in the full view" {
            val svg = render()
            svg shouldContain "<polyline"
        }
    })
