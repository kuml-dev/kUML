package dev.kuml.desktop.preview

import dev.kuml.cli.export.LatexExportResult
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.render.DesktopRenderPipeline
import dev.kuml.desktop.render.DesktopRenderResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

private fun sample(name: String): String =
    LatexExportTest::class.java
        .getResourceAsStream("/render-samples/$name.kuml.kts")!!
        .readBytes()
        .toString(Charsets.UTF_8)

private fun request(
    script: String,
    standalone: Boolean,
    themeName: String = "kuml",
) = LatexExportRequest(script = script, themeName = themeName, standalone = standalone)

class LatexExportTest :
    FunSpec({

        // --- file name -------------------------------------------------------------------------

        test("the suggested file name is the base name with .tex") {
            latexSuggestedFileName("order") shouldBe "order.tex"
            latexSuggestedFileName("order.kuml.kts") shouldBe "order.tex"
            latexSuggestedFileName(null) shouldBe "diagram.tex"
            latexSuggestedFileName("a/b:c") shouldBe "a_b_c.tex"
        }

        test("LatexMode maps to and from the stored boolean") {
            latexModeOf(true) shouldBe LatexMode.STANDALONE
            latexModeOf(false) shouldBe LatexMode.SNIPPET
            LatexMode.STANDALONE.isStandalone() shouldBe true
            LatexMode.SNIPPET.isStandalone() shouldBe false
        }

        // --- enablement ------------------------------------------------------------------------

        test("enablement: the first failing condition wins and a supported diagram in a script is available") {
            latexUnavailableReason(
                hasRenderedDiagram = true,
                renderFailed = false,
                latexCapable = true,
                sourceIsScript = true,
            ) shouldBe null
            latexUnavailableReason(
                hasRenderedDiagram = false,
                renderFailed = false,
                latexCapable = false,
                sourceIsScript = true,
            ) shouldBe LatexUnavailable.NOTHING_RENDERED
            latexUnavailableReason(
                hasRenderedDiagram = true,
                renderFailed = true,
                latexCapable = true,
                sourceIsScript = true,
            ) shouldBe LatexUnavailable.NOTHING_RENDERED
            latexUnavailableReason(
                hasRenderedDiagram = true,
                renderFailed = false,
                latexCapable = false,
                sourceIsScript = true,
            ) shouldBe LatexUnavailable.UNSUPPORTED_DIAGRAM
            // A Markdown (Knowledge-Workspace) document: disabled with its own reason, whatever else holds.
            latexUnavailableReason(
                hasRenderedDiagram = true,
                renderFailed = false,
                latexCapable = true,
                sourceIsScript = false,
            ) shouldBe LatexUnavailable.NOT_A_SCRIPT
        }

        test("every unavailable reason has a non-blank text in both languages, and they differ per cause") {
            for (strings in listOf(Strings.EN, Strings.DE)) {
                val texts = LatexUnavailable.entries.map { latexUnavailableText(reason = it, strings = strings) }
                texts.forEach { it.isNotBlank() shouldBe true }
                texts.toSet().size shouldBe LatexUnavailable.entries.size
            }
        }

        // --- capability of real diagrams -------------------------------------------------------

        test("the render result marks the supported families as LaTeX capable") {
            val supported =
                listOf(
                    "uml-class-order-domain",
                    "uml-sequence-place-order",
                    "uml-state-order-lifecycle",
                    "c4-context-internet-banking",
                    "sysml2-hybrid-vehicle",
                    "sysml2-ibd-vehicle",
                    "sysml2-library-system-uc",
                    "sysml2-vehicle-requirements",
                    "sysml2-traffic-light-stm",
                    "sysml2-order-processing-act",
                    "sysml2-login-flow-seq",
                    "sysml2-newton-second-law-par",
                    "bpmn-order-fulfillment",
                    "bpmn-collaboration-customer-supplier",
                    "blueprint-pdv-mitglieder-journey",
                )
            for (name in supported) {
                val result = DesktopRenderPipeline.render(script = sample(name), themeName = "kuml")
                result.shouldBeInstanceOf<DesktopRenderResult.Svg>().latexCapable shouldBe true
            }
        }

        test("choreography and conversation diagrams are not LaTeX capable") {
            for (name in listOf("bpmn-choreography-bestellprozess", "bpmn-conversation-pdv")) {
                val result = DesktopRenderPipeline.render(script = sample(name), themeName = "kuml")
                result.shouldBeInstanceOf<DesktopRenderResult.Svg>().latexCapable shouldBe false
            }
        }

        test("every LaTeX capable sample really exports, so the capability flag is not a promise the service breaks") {
            for (name in listOf(
                "uml-class-order-domain",
                "c4-context-internet-banking",
                "sysml2-traffic-light-stm",
                "bpmn-order-fulfillment",
            )) {
                val out = renderLatex(request = request(script = sample(name), standalone = true))
                val ready = out.shouldBeInstanceOf<LatexRenderOutcome.Ready>()
                ready.tex shouldStartWith "\\documentclass"
                ready.tex.trimEnd() shouldEndWith "\\end{document}"
                ready.diagramCount shouldBe 1
            }
        }

        // --- rendering -------------------------------------------------------------------------

        test("a standalone request and a snippet request differ only in the document skeleton") {
            val script = sample("uml-class-order-domain")
            val full = renderLatex(request = request(script = script, standalone = true)).shouldBeInstanceOf<LatexRenderOutcome.Ready>()
            val snippet = renderLatex(request = request(script = script, standalone = false)).shouldBeInstanceOf<LatexRenderOutcome.Ready>()
            snippet.tex shouldNotContain "\\documentclass"
            full.tex shouldContain snippet.tex.trim()
        }

        test("service results are mapped and nothing is swallowed") {
            val ok =
                renderLatex(request = request(script = "s", standalone = true, themeName = "t")) { _, _, _ ->
                    LatexExportResult.Success(tex = "T", diagramCount = 3, firstDiagramName = "D", warnings = emptyList())
                }
            ok shouldBe LatexRenderOutcome.Ready(tex = "T", diagramCount = 3, firstDiagramName = "D")

            renderLatex(request = request(script = "s", standalone = true, themeName = "t")) { _, _, _ ->
                LatexExportResult.Failure("boom")
            } shouldBe
                LatexRenderOutcome.Failed("boom")

            renderLatex(request = request(script = "s", standalone = true, themeName = "t")) { _, _, _ ->
                throw IllegalStateException("unexpected")
            } shouldBe
                LatexRenderOutcome.Failed("unexpected")
        }

        test("a script that no longer compiles is a visible failure") {
            val out = renderLatex(request = request(script = "this is not kotlin (", standalone = true))
            out.shouldBeInstanceOf<LatexRenderOutcome.Failed>().message.isNotBlank() shouldBe true
        }

        test("the request carries the script text and nothing from the screen, so a simulation can not leak into the file") {
            LatexExportRequest::class.java.declaredFields
                .filterNot { it.isSynthetic || it.name.startsWith("\$") }
                .map { it.name } shouldContainExactlyInAnyOrder listOf("script", "themeName", "standalone")
            val tex =
                renderLatex(request = request(script = sample("uml-state-order-lifecycle"), standalone = false))
                    .shouldBeInstanceOf<LatexRenderOutcome.Ready>()
                    .tex
            tex shouldNotContain "<svg"
            tex shouldNotContain "kuml-sim"
        }

        // --- dialog hint -----------------------------------------------------------------------

        test("the multi-diagram hint appears only for more than one diagram and names count and diagram") {
            latexDialogHint(diagramCount = 1, firstDiagramName = "A", strings = Strings.EN) shouldBe null
            latexDialogHint(diagramCount = 0, firstDiagramName = null, strings = Strings.EN) shouldBe null
            val en = latexDialogHint(diagramCount = 3, firstDiagramName = "Overview", strings = Strings.EN)!!
            en shouldContain "3"
            en shouldContain "Overview"
            val de = latexDialogHint(diagramCount = 2, firstDiagramName = "Übersicht", strings = Strings.DE)!!
            de shouldContain "2"
            de shouldContain "Übersicht"
            latexDialogHint(diagramCount = 2, firstDiagramName = "", strings = Strings.EN)!! shouldContain "-"
        }

        // --- saving ----------------------------------------------------------------------------

        test("saveLatex writes UTF-8, overwrites, and leaves no temp file behind") {
            val dir = Files.createTempDirectory("kuml-latex-save").toFile()
            try {
                val target = File(dir, "out.tex")
                saveLatex(file = target, tex = "first äöü").shouldBeInstanceOf<ExportSaveResult.Saved>()
                target.readText(Charsets.UTF_8) shouldBe "first äöü"
                saveLatex(file = target, tex = "second").shouldBeInstanceOf<ExportSaveResult.Saved>()
                target.readText(Charsets.UTF_8) shouldBe "second"
                dir.list()!!.toList() shouldBe listOf("out.tex")
            } finally {
                dir.deleteRecursively()
            }
        }

        test("saveLatex into a missing folder is a failure with a message and writes nothing") {
            val dir = Files.createTempDirectory("kuml-latex-save-missing").toFile()
            try {
                val result = saveLatex(file = File(File(dir, "no-such-folder"), "out.tex"), tex = "x")
                result.shouldBeInstanceOf<ExportSaveResult.Failed>().message.isNotBlank() shouldBe true
                dir.list()!!.toList() shouldBe emptyList()
            } finally {
                dir.deleteRecursively()
            }
        }

        test("saveLatex over a directory fails and keeps the directory") {
            val dir = Files.createTempDirectory("kuml-latex-save-dir").toFile()
            try {
                val target = File(dir, "out.tex").also { it.mkdir() }
                File(target, "keep.txt").writeText("keep")
                saveLatex(file = target, tex = "x").shouldBeInstanceOf<ExportSaveResult.Failed>()
                File(target, "keep.txt").readText() shouldBe "keep"
                dir.list()!!.toList() shouldBe listOf("out.tex")
            } finally {
                dir.deleteRecursively()
            }
        }
    })
