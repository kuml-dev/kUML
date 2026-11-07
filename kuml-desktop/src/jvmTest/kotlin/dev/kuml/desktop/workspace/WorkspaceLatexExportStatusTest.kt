package dev.kuml.desktop.workspace

import dev.kuml.cli.workspace.LatexReportLine
import dev.kuml.cli.workspace.LatexRunCounts
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException

private fun counts(
    failed: Int = 0,
    skipped: Int = 0,
    excluded: Int = 0,
    warnings: Int? = 0,
) = LatexRunCounts(failed = failed, skipped = skipped, excluded = excluded, warnings = warnings)

private fun done(
    counts: LatexRunCounts = counts(),
    openError: String? = null,
) = LatexUiState.Done(
    outDir = File("/tmp/x/out"),
    summary = listOf("Wrote 1 figure(s)", "0 failed"),
    lines = listOf(LatexReportLine(level = "NOTE", text = "NOTE a.md:1: n")),
    counts = counts,
    openError = openError,
)

class WorkspaceLatexExportStatusTest :
    FunSpec({
        test("every state maps to a view with the right tone") {
            latexStatusView(LatexUiState.Idle).tone() shouldBe StatusTone.NEUTRAL
            latexStatusView(LatexUiState.Preparing).tone() shouldBe StatusTone.NEUTRAL
            latexStatusView(LatexUiState.Writing).tone() shouldBe StatusTone.NEUTRAL
            latexStatusView(LatexUiState.Cancelled).tone() shouldBe StatusTone.NEUTRAL
            latexStatusView(LatexUiState.Busy).tone() shouldBe StatusTone.WARNING
            latexStatusView(LatexUiState.ConfirmOverwrite(existing = listOf("a"))).tone() shouldBe StatusTone.WARNING
            latexStatusView(LatexUiState.StrictBlocked(lines = emptyList(), counts = counts())).tone() shouldBe StatusTone.ERROR
            latexStatusView(LatexUiState.NothingToExport(lines = emptyList(), counts = counts(failed = 1))).tone() shouldBe StatusTone.ERROR
            latexStatusView(LatexUiState.Failed(message = "x")).tone() shouldBe StatusTone.ERROR
        }

        test("a finished run is green only without issues, amber with skipped, excluded or warnings, red with a failure") {
            latexStatusView(done(counts = counts())).tone() shouldBe StatusTone.SUCCESS
            latexStatusView(done(counts = counts(warnings = null))).tone() shouldBe StatusTone.SUCCESS
            latexStatusView(done(counts = counts(skipped = 1))).tone() shouldBe StatusTone.WARNING
            latexStatusView(done(counts = counts(excluded = 1))).tone() shouldBe StatusTone.WARNING
            latexStatusView(done(counts = counts(warnings = 2))).tone() shouldBe StatusTone.WARNING
            latexStatusView(done(counts = counts(failed = 1, skipped = 1))).tone() shouldBe StatusTone.ERROR
        }

        test("the main button waits while working or asking, and only rendering can be cancelled") {
            latexStatusView(LatexUiState.Idle).blocksPrimary() shouldBe false
            latexStatusView(LatexUiState.Preparing).blocksPrimary() shouldBe true
            latexStatusView(LatexUiState.Writing).blocksPrimary() shouldBe true
            latexStatusView(LatexUiState.ConfirmOverwrite(existing = emptyList())).blocksPrimary() shouldBe true
            latexStatusView(done()).blocksPrimary() shouldBe false
            latexStatusView(LatexUiState.Preparing).canCancel() shouldBe true
            latexStatusView(LatexUiState.Writing).canCancel() shouldBe false
            latexStatusView(LatexUiState.Idle).canCancel() shouldBe false
        }

        test("the overwrite view lists at most five names but counts all") {
            val v =
                latexStatusView(LatexUiState.ConfirmOverwrite(existing = (1..8).map { "f$it.tex" }))
                    .shouldBeInstanceOf<LatexStatusView.Overwrite>()
            v.count shouldBe 8
            v.names.size shouldBe 5
        }

        test("the output path in the view has no control characters") {
            val v = latexStatusView(done().copy(outDir = File("/tmp/a\u001B[31mb"))).shouldBeInstanceOf<LatexStatusView.Done>()
            v.outDir.none { Character.isISOControl(it) } shouldBe true
        }

        test("the report text is the summary followed by the protocol, verbatim") {
            latexReportText(done()) shouldBe "Wrote 1 figure(s)\n0 failed\nNOTE a.md:1: n"
        }

        test("a failing 'Show folder' is reported in the state instead of being swallowed") {
            val broken = showLatexFolder(done = done()) { throw IOException("no file manager") }
            broken.openError shouldBe "IOException"
            latexStatusView(broken).shouldBeInstanceOf<LatexStatusView.Done>().openError shouldBe "IOException"
            var opened: File? = null
            val ok = showLatexFolder(done = broken) { opened = it }
            ok.openError shouldBe null
            opened shouldBe File("/tmp/x/out")
        }
    })
