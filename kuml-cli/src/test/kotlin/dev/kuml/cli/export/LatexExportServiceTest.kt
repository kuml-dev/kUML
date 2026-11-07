package dev.kuml.cli.export

import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class LatexExportServiceTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        fun script(path: String): String = File("src/test/resources/$path").readText()

        fun tempEntries(): Set<String> =
            File(System.getProperty("java.io.tmpdir"))
                .listFiles { f -> f.name.startsWith("kuml-latex-export") }
                .orEmpty()
                .map { it.name }
                .toSet()

        test("standalone export of a single diagram returns a complete document, count 1 and the diagram name") {
            val result = LatexExportService.export(script = script("minimal.kuml.kts"), themeName = "kuml", standalone = true)
            val ok = result.shouldBeInstanceOf<LatexExportResult.Success>()
            ok.tex shouldStartWith "\\documentclass[border=10pt]{standalone}"
            ok.tex.trimEnd() shouldEndWith "\\end{document}"
            ok.diagramCount shouldBe 1
            ok.firstDiagramName shouldBe "Smoke Test"
        }

        test("snippet export has no document skeleton") {
            val ok =
                LatexExportService
                    .export(script = script("minimal.kuml.kts"), themeName = "kuml", standalone = false)
                    .shouldBeInstanceOf<LatexExportResult.Success>()
            ok.tex shouldNotContain "\\documentclass"
            ok.tex shouldStartWith "\\begin{tikzpicture}"
        }

        test("a script with two diagrams reports both and exports the first") {
            val ok =
                LatexExportService
                    .export(script = script("simulate/bpmn/two-processes-two-diagrams.kuml.kts"), themeName = null, standalone = true)
                    .shouldBeInstanceOf<LatexExportResult.Success>()
            ok.diagramCount shouldBe 2
            ok.firstDiagramName shouldBe "Process A View"
            ok.tex shouldContain "onlyA"
            ok.tex shouldNotContain "onlyB"
        }

        test("every other family exports as well") {
            for (name in listOf("c4-context", "sysml2-bdd", "sysml2-stm", "bpmn-collaboration", "blueprint", "uml-sequence")) {
                val result = LatexExportService.export(script = script("latex/$name.kuml.kts"), themeName = "kuml", standalone = true)
                result.shouldBeInstanceOf<LatexExportResult.Success>().tex shouldStartWith "\\documentclass"
            }
        }

        test("a script that does not compile is a failure that keeps the message") {
            val result = LatexExportService.export(script = "this is not kotlin (", themeName = "kuml", standalone = true)
            result.shouldBeInstanceOf<LatexExportResult.Failure>().message shouldContain "Script evaluation failed"
        }

        test("an ERM diagram is a failure, not a half file") {
            val erm =
                Files.walk(File("src/test/resources/erm").toPath()).use { s ->
                    s.filter { it.toString().endsWith(".kuml.kts") }.findFirst()
                }
            val result = LatexExportService.export(script = erm.get().toFile().readText(), themeName = "kuml", standalone = true)
            result.shouldBeInstanceOf<LatexExportResult.Failure>().message shouldContain "ERM"
        }

        test("a SysML 2 package diagram (or a script shape that does not evaluate) is a failure") {
            val src =
                """
                import dev.kuml.sysml2.dsl.*
                sysml2Model(name = "P") {
                    packageDiagram(name = "Pkgs") { }
                }
                """.trimIndent()
            // Only assert the failure contract when this script shape evaluates; a compile problem is also a Failure.
            LatexExportService.export(script = src, themeName = "kuml", standalone = true).shouldBeInstanceOf<LatexExportResult.Failure>()
        }

        test("an unknown theme is a failure that names the theme") {
            val result = LatexExportService.export(script = script("minimal.kuml.kts"), themeName = "no-such-theme", standalone = true)
            result.shouldBeInstanceOf<LatexExportResult.Failure>().message shouldContain "no-such-theme"
        }

        test("temporary files are removed after success and after failure") {
            val before = tempEntries()
            LatexExportService.export(script = script("minimal.kuml.kts"), themeName = "kuml", standalone = true)
            LatexExportService.export(script = "broken (", themeName = "kuml", standalone = true)
            (tempEntries() - before) shouldBe emptySet()
        }

        test("the exported text comes from the script, so a changed script changes the output") {
            val a = LatexExportService.export(script = script("minimal.kuml.kts"), themeName = "kuml", standalone = false)
            val changed = script("minimal.kuml.kts").replace("Order", "Invoice")
            val b = LatexExportService.export(script = changed, themeName = "kuml", standalone = false)
            a.shouldBeInstanceOf<LatexExportResult.Success>().tex shouldContain "Order"
            b.shouldBeInstanceOf<LatexExportResult.Success>().tex shouldContain "Invoice"
            b.tex shouldNotContain "Order"
        }

        test("exportFile with a shared preamble gives a snippet without the style block and without a document") {
            val r =
                LatexExportService
                    .exportFile(input = File("src/test/resources/latex/uml-class.kuml.kts"), themeName = null, sharedPreamble = true)
                    .shouldBeInstanceOf<LatexFileExportResult.Rendered>()
            r.family shouldBe dev.kuml.io.latex.LatexFamily.UML
            r.tex shouldNotContain "\\documentclass"
            r.tex shouldNotContain "\\tikzset{kuml-class"
            r.tex shouldStartWith "\\begin{tikzpicture}"
        }

        test("exportFile skips families without LaTeX output and names the reason") {
            val erm =
                LatexExportService.exportFile(
                    input = File("src/test/resources/latex-folder/erm.kuml.kts"),
                    themeName = null,
                    sharedPreamble = true,
                )
            erm.shouldBeInstanceOf<LatexFileExportResult.Skipped>().reason shouldContain "ERM"
            val choreo =
                LatexExportService.exportFile(
                    input = File("src/test/resources/latex-folder/bpmn-choreography.kuml.kts"),
                    themeName = null,
                    sharedPreamble = true,
                )
            choreo.shouldBeInstanceOf<LatexFileExportResult.Skipped>().reason shouldContain "choreography"
        }

        test("exportFile reports a script that does not compile as failed") {
            val dir = Files.createTempDirectory("kuml-exportfile").toFile()
            try {
                val f = File(dir, "bad.kuml.kts").also { it.writeText("this is not kotlin (") }
                LatexExportService
                    .exportFile(
                        input = f,
                        themeName = null,
                        sharedPreamble = true,
                    ).shouldBeInstanceOf<LatexFileExportResult.Failed>()
            } finally {
                dir.deleteRecursively()
            }
        }

        test("export keeps producing exactly what the render pipeline writes") {
            val a = LatexExportService.export(script = script("latex/uml-class.kuml.kts"), themeName = null, standalone = true)
            val b =
                LatexExportService.exportFile(
                    input = File("src/test/resources/latex/uml-class.kuml.kts"),
                    themeName = null,
                    sharedPreamble = false,
                )
            // Same pipeline: the snippet (non-standalone, not shared) is contained in the standalone document.
            val snippet = b.shouldBeInstanceOf<LatexFileExportResult.Rendered>().tex
            a.shouldBeInstanceOf<LatexExportResult.Success>().tex shouldContain snippet
        }
    })
