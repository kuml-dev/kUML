package dev.kuml.io.latex

import dev.kuml.core.model.note.NoteKind
import dev.kuml.io.latex.note.NoteFixtures
import dev.kuml.io.latex.note.Pdflatex
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private fun libraryLines(tex: String): List<String> = tex.lines().filter { it.startsWith("\\usetikzlibrary") }

private fun occurrences(
    text: String,
    needle: String,
): Int = text.windowed(needle.length).count { it == needle }

class SharedPreambleTest :
    StringSpec({
        val base = "\\usetikzlibrary{arrows.meta, calc, positioning}"

        "library lines per single family" {
            for (f in listOf(LatexFamily.UML, LatexFamily.SYSML2, LatexFamily.C4, LatexFamily.ERM)) {
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
            // lmodern is guarded by the font defaults, not by ifpackageloaded, so it cannot override a host font
            // \rmdefault & co. are \long macros in some TeX Live releases (2023), so they are compared as \edef-expanded
            // text; a direct \ifx against a plain \def never matches there and lmodern would silently not be loaded.
            tex shouldContain "\\edef\\kumlrm{\\rmdefault}"
            tex shouldContain "\\ifx\\kumlrm\\kumlcmr"
            tex shouldNotContain "\\ifx\\rmdefault"
            tex shouldContain "\\usepackage{lmodern}\\fi\\fi\\fi"
            tex.indexOf("{lmodern}") shouldBeGreaterThan tex.indexOf("{fontenc}")
        }

        // The preamble is \input into host documents: it must not reset a font the host already chose.
        "shared preamble keeps a host document font default and sets lmodern only on the CM defaults".config(enabled = Pdflatex.enabled) {
            fun rmAfterPreamble(hostFont: String): String {
                val dir = Files.createTempDirectory("kuml-preamble-font-").toFile()
                try {
                    java.io.File(dir, "kuml-preamble.tex").writeText(KumlLatexRenderer.sharedPreamble(setOf(LatexFamily.UML)))
                    java.io.File(dir, "host.tex").writeText(
                        "\\documentclass{article}\n$hostFont\n\\input{kuml-preamble}\n" +
                            "\\begin{document}\\typeout{RM=\\rmdefault=END}\\end{document}\n",
                    )
                    val process =
                        ProcessBuilder(
                            Pdflatex.requireExecutable().path,
                            "-no-shell-escape",
                            "-interaction=nonstopmode",
                            "-halt-on-error",
                            "host.tex",
                        ).directory(dir).redirectErrorStream(true).start()
                    process.outputStream.close()
                    val output = process.inputStream.bufferedReader().readText()
                    check(process.waitFor(60, TimeUnit.SECONDS)) { "pdflatex timed out" }
                    check(process.exitValue() == 0) { "pdflatex failed:\n${output.takeLast(2000)}" }
                    return output.substringAfter("RM=").substringBefore("=END")
                } finally {
                    dir.deleteRecursively()
                }
            }
            rmAfterPreamble("\\renewcommand{\\rmdefault}{ppl}") shouldBe "ppl"
            rmAfterPreamble("") shouldBe "lmr"
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
                    "erm" to
                        (
                            dev.kuml.io.latex.erm.ErmLatexFixtures
                                .render() to
                                dev.kuml.io.latex.erm.ErmLatexFixtures
                                    .render(options = LatexRenderOptions(sharedPreamble = true))
                        ),
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
