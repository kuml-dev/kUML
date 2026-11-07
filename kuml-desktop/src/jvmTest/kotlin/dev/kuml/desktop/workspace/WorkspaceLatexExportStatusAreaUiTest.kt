package dev.kuml.desktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kuml.cli.workspace.LatexReportLine
import dev.kuml.cli.workspace.LatexRunCounts
import dev.kuml.desktop.i18n.Strings
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Compose UI tests of the LaTeX status area; semantics and text only, never pixels. */
@OptIn(ExperimentalTestApi::class)
@Tags("compose-ui")
class WorkspaceLatexExportStatusAreaUiTest :
    FunSpec({
        val en = Strings.EN
        val s = en.workspaceLatexExport
        val counts = LatexRunCounts(failed = 0, skipped = 1, excluded = 0, warnings = null)
        val summary =
            listOf("Wrote 1 figure(s) plus kuml-preamble.tex and main.tex: /x/out", "0 failed, 1 skipped, 0 excluded from main.tex")
        val lines = listOf(LatexReportLine(level = "SKIP", text = "SKIP erm.kuml.kts: ERM diagrams have no LaTeX output yet"))

        fun done(openError: String? = null) =
            LatexStatusView.Done(outDir = "/x/out", summary = summary, lines = lines, counts = counts, openError = openError)

        fun show(
            view: LatexStatusView,
            canOpenFolder: Boolean = true,
            onShowFolder: () -> Unit = {},
            onCopyReport: () -> Unit = {},
            onOverwrite: () -> Unit = {},
            onKeepExisting: () -> Unit = {},
            block: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
        ) = runComposeUiTest {
            setContent {
                Box(Modifier.heightIn(max = 400.dp)) {
                    WorkspaceLatexStatusArea(
                        view = view,
                        strings = en,
                        canOpenFolder = canOpenFolder,
                        onShowFolder = onShowFolder,
                        onCopyReport = onCopyReport,
                        onOverwrite = onOverwrite,
                        onKeepExisting = onKeepExisting,
                    )
                }
            }
            block()
        }

        test("done shows the count lines and the protocol verbatim, and both actions work") {
            var folder = 0
            var copy = 0
            show(done(), onShowFolder = { folder++ }, onCopyReport = { copy++ }) {
                onNodeWithText(summary[0]).assertExists()
                onNodeWithText(summary[1]).assertExists()
                onNodeWithText(lines[0].text).assertExists()
                onNodeWithText(s.doneHeadline.format("/x/out")).assertExists()
                onNodeWithText(s.showFolder).performClick()
                onNodeWithText(s.copyReport).performClick()
            }
            folder shouldBe 1
            copy shouldBe 1
        }

        test("without a file manager there is no 'Show folder' button") {
            show(done(), canOpenFolder = false) {
                onAllNodesWithText(s.showFolder).fetchSemanticsNodes().size shouldBe 0
                onNodeWithText(s.copyReport).assertExists()
            }
        }

        test("a failed 'Show folder' is visible in the banner") {
            show(done(openError = "IOException")) {
                onNodeWithText(s.openFolderFailed.format("IOException")).assertExists()
            }
        }

        test("strict-blocked shows the protocol and the refusal") {
            show(LatexStatusView.StrictBlocked(lines = lines, counts = counts)) {
                onNodeWithText(s.strictBlockedHeadline).assertExists()
                onNodeWithText(lines[0].text).assertExists()
            }
        }

        test("the overwrite question lists the files and both answers trigger their callbacks") {
            var replace = 0
            var keep = 0
            show(
                LatexStatusView.Overwrite(count = 2, names = listOf("main.tex", "a.tex")),
                onOverwrite = { replace++ },
                onKeepExisting = { keep++ },
            ) {
                onNodeWithText(s.overwriteHeadline.format(2, "main.tex, a.tex")).assertExists()
                onNodeWithText(s.overwriteButton).performClick()
                onNodeWithText(s.keepExisting).performClick()
            }
            replace shouldBe 1
            keep shouldBe 1
        }

        test("a long protocol is capped and says how many lines are hidden") {
            val many = (1..60).map { LatexReportLine(level = "NOTE", text = "NOTE line $it") }
            val shown = shownLines(lines = many, moreFormat = s.moreLines)
            shown.size shouldBe 41
            shown.last() shouldBe s.moreLines.format(20)
        }

        test("busy and failed use their own texts") {
            show(LatexStatusView.Failed(message = null, ioClass = null)) { onNodeWithText(s.busy).assertExists() }
            show(LatexStatusView.Failed(message = "IOException", ioClass = "IOException")) {
                onNodeWithText(s.failedHeadline.format(s.ioError.format("IOException"))).assertExists()
            }
        }
    })
