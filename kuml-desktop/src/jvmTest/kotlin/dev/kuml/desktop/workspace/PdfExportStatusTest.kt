package dev.kuml.desktop.workspace

import dev.kuml.desktop.i18n.Strings
import dev.kuml.workspace.OkfFinding
import dev.kuml.workspace.OkfSeverity
import dev.kuml.workspace.pdf.PageFormat
import dev.kuml.workspace.pdf.PageOrientation
import dev.kuml.workspace.pdf.PageStrategy
import dev.kuml.workspace.pdf.PdfLayoutSummary
import dev.kuml.workspace.pdf.ScaleWarning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

private fun scale(i: Int) =
    ScaleWarning(documentPath = "doc$i.md", blockIndex = i, title = "t", scale = 0.3, suggestion = "s", message = "illegible $i")

private fun failure(i: Int) = OkfFinding(code = "X", severity = OkfSeverity.ERROR, file = "f$i.md", line = i, message = "m")

private fun warnings(
    scales: Int = 0,
    failures: Int = 0,
    summary: PdfLayoutSummary = PdfLayoutSummary.EMPTY,
) = PdfUiState.Warnings(warnings = (1..scales).map(::scale), failures = (1..failures).map(::failure), summary = summary)

private fun warningView(
    ui: PdfUiState.Warnings,
    form: PdfExportFormState = PdfExportFormState(),
) = pdfStatusView(ui = ui, form = form).shouldBeInstanceOf<PdfStatusView.Warning>()

class PdfExportStatusTest :
    FunSpec({
        test("all UI states map to a view with the right tone") {
            pdfStatusView(ui = PdfUiState.Idle, form = PdfExportFormState()) shouldBe PdfStatusView.Idle
            pdfStatusView(ui = PdfUiState.Idle, form = PdfExportFormState()).tone() shouldBe StatusTone.NEUTRAL
            val checking = pdfStatusView(ui = PdfUiState.Preparing(done = 2, total = 5), form = PdfExportFormState())
            checking shouldBe PdfStatusView.Checking(done = 2, total = 5)
            checking.tone() shouldBe StatusTone.NEUTRAL
            pdfStatusView(ui = warnings(scales = 1), form = PdfExportFormState()).tone() shouldBe StatusTone.WARNING
            pdfStatusView(ui = warnings(scales = 1, failures = 1), form = PdfExportFormState()).tone() shouldBe StatusTone.ERROR
            val overwrite = pdfStatusView(ui = PdfUiState.ConfirmOverwrite(target = File("a.pdf")), form = PdfExportFormState())
            overwrite shouldBe PdfStatusView.Overwrite(count = 0, names = emptyList())
            overwrite.tone() shouldBe StatusTone.WARNING
            pdfStatusView(ui = PdfUiState.Done(path = "/x.pdf"), form = PdfExportFormState()).tone() shouldBe StatusTone.SUCCESS
            pdfStatusView(ui = PdfUiState.Failed(message = "boom"), form = PdfExportFormState()) shouldBe
                PdfStatusView.Failed(message = "boom", retryable = true)
            pdfStatusView(ui = PdfUiState.Failed(message = "boom"), form = PdfExportFormState()).tone() shouldBe StatusTone.ERROR
            // The renderer being busy is a transient hint, not an error.
            val busy = pdfStatusView(ui = PdfUiState.Busy, form = PdfExportFormState())
            busy shouldBe PdfStatusView.Failed(message = null, retryable = true)
            busy.tone() shouldBe StatusTone.WARNING
        }

        test("samples are cut to 3 entries and the rest is counted") {
            val v = warningView(ui = warnings(scales = 5, failures = 4))
            v.scaleSample.size shouldBe 3
            v.failureSample shouldBe listOf("f1.md:1", "f2.md:2", "f3.md:3")
            v.hiddenCount shouldBe 3
            v.scaleSample[0] shouldBe "doc1.md#2 (illegible 1)"
            v.scaleSample[1] shouldBe "doc2.md#3"
        }

        test("hidden count at the boundaries 0, 3 and 4") {
            warningView(ui = warnings(scales = 0, failures = 1)).hiddenCount shouldBe 0
            warningView(ui = warnings(scales = 3, failures = 3)).hiddenCount shouldBe 0
            warningView(ui = warnings(scales = 4, failures = 3)).hiddenCount shouldBe 1
        }

        test("overwrite lists at most 5 existing files") {
            val v =
                pdfStatusView(
                    ui = PdfUiState.ConfirmOverwrite(target = File("d"), existing = (1..7).map { "$it.pdf" }),
                    form = PdfExportFormState(),
                )
            v shouldBe PdfStatusView.Overwrite(count = 7, names = listOf("1.pdf", "2.pdf", "3.pdf", "4.pdf", "5.pdf"))
        }

        test("A3 landscape is offered only for a fixed page size that is not already A3 landscape") {
            val fixedA4 = PdfExportFormState(pageStrategy = PageStrategy.FIXED, page = PageFormat.A4)
            warningView(ui = warnings(scales = 1), form = fixedA4).offerA3Landscape shouldBe true
            val fixedA3Landscape = fixedA4.copy(page = PageFormat.A3, orientation = PageOrientation.LANDSCAPE)
            warningView(ui = warnings(scales = 1), form = fixedA3Landscape).offerA3Landscape shouldBe false
            val fixedA3Portrait = fixedA4.copy(page = PageFormat.A3, orientation = PageOrientation.PORTRAIT)
            warningView(ui = warnings(scales = 1), form = fixedA3Portrait).offerA3Landscape shouldBe true
            val auto = warningView(ui = warnings(scales = 1), form = PdfExportFormState())
            auto.offerA3Landscape shouldBe false
            auto.hintMinScale shouldBe true
            val noScale = warningView(ui = warnings(failures = 1), form = fixedA4)
            noScale.offerA3Landscape shouldBe false
            noScale.hintMinScale shouldBe false
            warningView(ui = warnings(failures = 1), form = PdfExportFormState()).hintMinScale shouldBe false
            warningView(ui = warnings(scales = 1), form = fixedA4).hintMinScale shouldBe false
        }

        test("only warnings and overwrite block the primary button") {
            val form = PdfExportFormState()
            pdfStatusView(ui = warnings(scales = 1), form = form).blocksPrimary() shouldBe true
            pdfStatusView(ui = PdfUiState.ConfirmOverwrite(target = File("a")), form = form).blocksPrimary() shouldBe true
            listOf(
                PdfUiState.Idle,
                PdfUiState.Preparing(done = 0, total = 0),
                PdfUiState.Done(path = "p"),
                PdfUiState.Failed(message = "m"),
                PdfUiState.Busy,
            ).forEach { pdfStatusView(ui = it, form = form).blocksPrimary() shouldBe false }
        }

        test("layout note is null without A3 or tiled diagrams") {
            warningView(
                ui =
                    warnings(
                        scales = 1,
                        summary = PdfLayoutSummary(a3Diagrams = 0, tiledDiagrams = 0, tilePages = 0, illegibleDiagrams = 1),
                    ),
            ).layoutNote shouldBe
                null
            warningView(
                ui =
                    warnings(
                        scales = 1,
                        summary = PdfLayoutSummary(a3Diagrams = 2, tiledDiagrams = 1, tilePages = 4, illegibleDiagrams = 1),
                    ),
            ).layoutNote shouldBe
                LayoutNote(a3Diagrams = 2, tiledDiagrams = 1, tilePages = 4)
        }

        test("withA3Landscape sets page, strategy and orientation and keeps everything else") {
            val before = PdfExportFormState(preset = PdfPreset.DIAGRAMS, author = "Eva", draft = true, minScalePercent = 35)
            val after = before.withA3Landscape()
            after.page shouldBe PageFormat.A3
            after.pageStrategy shouldBe PageStrategy.FIXED
            after.orientation shouldBe PageOrientation.LANDSCAPE
            after.copy(page = before.page, pageStrategy = before.pageStrategy, orientation = before.orientation) shouldBe before
        }

        test("checking without a known total is indeterminate") {
            (
                pdfStatusView(
                    ui = PdfUiState.Preparing(done = 0, total = 0),
                    form = PdfExportFormState(),
                ) as PdfStatusView.Checking
            ).total shouldBe
                0
        }

        test("the warning headlines stay within 60 characters in both languages") {
            listOf(Strings.DE, Strings.EN).forEach { s ->
                (
                    s.pdfStatus.warnHeadScale
                        .format(999)
                        .length <= 60
                ) shouldBe true
                (
                    s.pdfStatus.warnHeadFailures
                        .format(999)
                        .length <= 60
                ) shouldBe true
            }
        }
    })
