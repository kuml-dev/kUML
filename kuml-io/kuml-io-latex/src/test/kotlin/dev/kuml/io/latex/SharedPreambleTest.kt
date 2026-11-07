package dev.kuml.io.latex

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.note.NoteFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private fun libraryLines(tex: String): List<String> = tex.lines().filter { it.startsWith("\\usetikzlibrary") }

private fun occurrences(
    text: String,
    needle: String,
): Int = text.windowed(needle.length).count { it == needle }

class SharedPreambleTest :
    StringSpec({
        val base = "\\usetikzlibrary{arrows.meta, calc, positioning}"

        "library lines per single family" {
            for (f in listOf(LatexFamily.UML, LatexFamily.SYSML2, LatexFamily.C4)) {
                libraryLines(KumlLatexRenderer.sharedPreamble(setOf(f))) shouldBe listOf(base)
            }
            libraryLines(KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.BPMN))) shouldBe
                listOf(base, "\\usetikzlibrary{shapes.geometric}")
            libraryLines(KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.BLUEPRINT))) shouldBe
                listOf(base, "\\usetikzlibrary{shapes.geometric, plotmarks}")
        }

        "union of families is deterministic and deduplicated" {
            val a = KumlLatexRenderer.sharedPreamble(linkedSetOf(LatexFamily.UML, LatexFamily.BPMN, LatexFamily.BLUEPRINT))
            val b = KumlLatexRenderer.sharedPreamble(linkedSetOf(LatexFamily.BLUEPRINT, LatexFamily.BPMN, LatexFamily.UML))
            a shouldBe b
            libraryLines(a) shouldBe listOf(base, "\\usetikzlibrary{shapes.geometric, plotmarks}")
        }

        "shared preamble has no document structure and one style block" {
            val tex = KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.UML))
            tex shouldNotContain "\\documentclass"
            tex shouldNotContain "\\begin{document}"
            occurrences(text = tex, needle = "\\tikzset{") shouldBe 1
            tex shouldContain KumlLatexRenderer.tikzStyleBlock("")
            tex shouldContain "\\@ifpackageloaded{babel}{}{\\usepackage[english]{babel}}"
            tex shouldContain "\\@ifpackageloaded{tikz}{}{\\usepackage{tikz}}"
        }

        "library lines match the standalone output of each family" {
            val standalone =
                mapOf(
                    LatexFamily.UML to NoteFixtures.renderUml(standalone = true),
                    LatexFamily.BPMN to NoteFixtures.renderBpmnProcess(standalone = true),
                )
            for ((family, tex) in standalone) {
                libraryLines(KumlLatexRenderer.sharedPreamble(setOf(family))) shouldBe libraryLines(tex)
            }
            val blueprint =
                dev.kuml.io.latex.note.NoteFixtures
                    .renderBlueprint(standalone = true)
            libraryLines(KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.BLUEPRINT))) shouldBe libraryLines(blueprint)
        }

        "snippets with a shared preamble equal the default snippet minus the style block" {
            val style = KumlLatexRenderer.tikzStyleBlock("  ")
            val pairs =
                mapOf(
                    "uml" to (NoteFixtures.renderUml() to NoteFixtures.renderUml(shared = true)),
                    "uml-note" to
                        (
                            NoteFixtures.renderUml(body = "Hello", kind = NoteKind.INFO) to
                                NoteFixtures.renderUml(body = "Hello", kind = NoteKind.INFO, shared = true)
                        ),
                    "bdd" to (NoteFixtures.renderBdd() to NoteFixtures.renderBdd(shared = true)),
                    "stm" to (NoteFixtures.renderStm() to NoteFixtures.renderStm(shared = true)),
                    "c4" to (NoteFixtures.renderC4() to NoteFixtures.renderC4(shared = true)),
                    "c4-note" to
                        (
                            NoteFixtures.renderC4(body = "Hello", kind = NoteKind.NOTE) to
                                NoteFixtures.renderC4(body = "Hello", kind = NoteKind.NOTE, shared = true)
                        ),
                    "bpmn" to (NoteFixtures.renderBpmnProcess() to NoteFixtures.renderBpmnProcess(shared = true)),
                    "bpmn-collab" to
                        (NoteFixtures.renderBpmnCollaboration() to NoteFixtures.renderBpmnCollaboration(shared = true)),
                    "journey" to (NoteFixtures.renderBlueprint() to NoteFixtures.renderBlueprint(shared = true)),
                    "full" to (NoteFixtures.renderBlueprint(full = true) to NoteFixtures.renderBlueprint(full = true, shared = true)),
                )
            for ((name, p) in pairs) {
                val (normal, shared) = p
                occurrences(text = normal, needle = style) shouldBe 1
                shared shouldBe normal.replace(style, "")
                shared shouldNotContain "\\documentclass"
                if (name.endsWith("note")) shared shouldContain "\\tikzset{"
            }
        }

        "standalone and sharedPreamble are mutually exclusive" {
            shouldThrow<IllegalArgumentException> { LatexRenderOptions(standalone = true, sharedPreamble = true) }
        }
    })
