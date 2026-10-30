package dev.kuml.io.svg.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintDiagramFull
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.svg.KumlSvgRenderer
import dev.kuml.io.svg.SampleOutput
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** P-COM-04: Service Blueprint notes (column right of the lanes, dotted anchor lines). */
class BlueprintNotesSvgTest :
    StringSpec({
        fun model(withNotes: Boolean) =
            blueprint(name = "Service") {
                val staff = actor(name = "Mitarbeiter", role = ActorRole.STAFF)
                lateinit var fill: String
                lateinit var check: String
                phase(name = "Antrag") {
                    fill = customer(name = "Fuellt Formular", sentiment = Sentiment.NEUTRAL)
                    check = backstage(name = "Prueft Angaben", actor = staff)
                }
                phase(name = "Aufnahme") {
                    customer(name = "Wartet", sentiment = Sentiment.NEGATIVE)
                }
                if (withNotes) {
                    note(text = "ANNAHME A1: Antrag <b>digital</b>", fill, check, id = "n1")
                    note(text = "Freistehend", id = "free")
                }
                blueprintDiagram(name = "Blueprint", emotionCurve = false)
            }

        fun render(m: dev.kuml.blueprint.model.BlueprintModel) =
            KumlSvgRenderer.toSvg(model = m, diagram = m.diagrams.first() as BlueprintDiagramFull)

        "notes and their dotted anchor lines are drawn, text escaped" {
            val svg = render(model(withNotes = true))
            SampleOutput.write(filename = "blueprint/notes.svg", content = svg)
            svg shouldContain "id=\"n1\""
            svg shouldContain "id=\"free\""
            svg shouldContain "ANNAHME A1"
            svg shouldContain "&lt;b&gt;digital&lt;/b&gt;"
            svg shouldNotContain "<b>digital"
            Regex("class=\"kuml-note-anchor\"").findAll(svg).count() shouldBe 2
            Regex("class=\"kuml-note-anchor-dot\"").findAll(svg).count() shouldBe 2
        }

        "a model without notes renders exactly as before (no note column)" {
            val svg = render(model(withNotes = false))
            svg shouldNotContain "kuml-blueprint-note"
            val withNotes = render(model(withNotes = true))
            val w = Regex("width=\"([0-9.]+)\"").find(svg)!!.groupValues[1].toDouble()
            val w2 = Regex("width=\"([0-9.]+)\"").find(withNotes)!!.groupValues[1].toDouble()
            (w2 > w) shouldBe true
        }
    })
