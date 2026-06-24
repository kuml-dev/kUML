package dev.kuml.io.latex.blueprint

import dev.kuml.blueprint.dsl.blueprint
import dev.kuml.blueprint.model.ActorRole
import dev.kuml.blueprint.model.BlueprintLine
import dev.kuml.blueprint.model.ChannelKind
import dev.kuml.blueprint.model.Sentiment
import dev.kuml.io.latex.KumlLatexRenderer
import dev.kuml.io.latex.LatexRenderOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * LaTeX/TikZ renderer tests for Journey / Service Blueprint (V3.1.26).
 * Mirrors the SVG structure: bands, lines, emotion curve, steps, connections.
 */
class BlueprintLatexRendererTest :
    StringSpec({
        fun fullModel() =
            blueprint("Service") {
                val staff = actor("Mitarbeiter", ActorRole.STAFF)
                val system = actor("CRM", ActorRole.SYSTEM)
                val web = channel("Web", ChannelKind.WEB)
                val formular = touchpoint("Formular", channel = web)
                phase("Antrag") {
                    customer("Füllt Formular", Sentiment.NEUTRAL, touchpoints = listOf(formular))
                    frontstage("Bestätigt", actor = staff)
                    backstage("Prüft", actor = staff)
                    support("Speichert", actor = system)
                }
                phase("Aufnahme") {
                    customer("Wartet", Sentiment.NEGATIVE)
                    support("Weist zu", actor = system)
                }
                blueprintDiagram("Blueprint", emotionCurve = true)
            }

        fun render(standalone: Boolean = false): String =
            KumlLatexRenderer.toLatex(fullModel(), LatexRenderOptions(standalone = standalone))

        "emits a tikzpicture block" {
            val tex = render()
            tex shouldContain "\\begin{tikzpicture}"
            tex shouldContain "\\end{tikzpicture}"
        }

        "emits blueprint tikz styles in the tikzset block" {
            val tex = render()
            tex shouldContain "kuml-bp-step/.style"
            tex shouldContain "kuml-bp-line-visibility/.style"
            tex shouldContain "kuml-bp-emotion/.style"
        }

        "renders phase headers and layer labels" {
            val tex = render()
            tex shouldContain "Antrag"
            tex shouldContain "Aufnahme"
            tex shouldContain "Customer Actions"
            tex shouldContain "Support Processes"
        }

        "renders step nodes" {
            val tex = render()
            tex shouldContain """\node[kuml-bp-step]"""
            tex shouldContain "Formular" // step text present
        }

        "renders the emotion curve as a plot coordinates draw" {
            val tex = render()
            tex shouldContain "\\draw[kuml-bp-emotion] plot coordinates"
        }

        "renders all three separator lines with their styles" {
            val tex = render()
            tex shouldContain "kuml-bp-line-interaction"
            tex shouldContain "kuml-bp-line-visibility"
            tex shouldContain "kuml-bp-line-internal"
            tex shouldContain "Line of Interaction"
            tex shouldContain "Line of Visibility"
            tex shouldContain "Line of Internal Interaction"
        }

        "respects showLines subset" {
            val model = fullModel()
            val full = model.diagrams.first() as dev.kuml.blueprint.model.BlueprintDiagramFull
            val tex = KumlLatexRenderer.toLatex(model, full.copy(showLines = setOf(BlueprintLine.VISIBILITY)))
            tex shouldContain "Line of Visibility"
            tex shouldNotContain "Line of Interaction"
        }

        "standalone wraps a full document with required tikz libraries" {
            val tex = render(standalone = true)
            tex shouldContain "\\documentclass[border=10pt]{standalone}"
            tex shouldContain "\\usetikzlibrary{shapes.geometric, plotmarks}"
            tex shouldContain "\\begin{document}"
            tex shouldContain "\\end{document}"
        }

        "snippet mode does not emit a document wrapper" {
            val tex = render(standalone = false)
            tex shouldNotContain "\\begin{document}"
        }

        "empty model renders empty string" {
            val empty = blueprint("Empty") {}
            KumlLatexRenderer.toLatex(empty) shouldContain ""
        }
    })
