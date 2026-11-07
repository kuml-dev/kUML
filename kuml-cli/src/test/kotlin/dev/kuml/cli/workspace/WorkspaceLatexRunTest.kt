package dev.kuml.cli.workspace

import dev.kuml.cli.export.LatexFolderExportException
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.workspace.WorkspaceScanner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class WorkspaceLatexRunTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        val res = File("src/test/resources")

        fun knowledge(): File {
            val root = Files.createTempDirectory("kuml-run-k").toFile()
            File(res, "latex-knowledge").copyRecursively(root)
            return WorkspaceScanner.canonicalRoot(root)
        }

        fun engineering(broken: Boolean = false): File {
            val root = Files.createTempDirectory("kuml-run-e").toFile()
            File(res, "latex/uml-class.kuml.kts").copyTo(File(root, "a.kuml.kts"))
            if (broken) File(root, "broken.kuml.kts").writeText("this is not kotlin (")
            return WorkspaceScanner.canonicalRoot(root)
        }

        test("detect: scripts mean Engineering, a Markdown workspace Knowledge") {
            val k = knowledge()
            val e = engineering()
            try {
                val ex = { r: File -> WorkspaceLatexRun.excludes(root = r, target = WorkspaceLatexRun.defaultTarget(r)) }
                (WorkspaceLatexRun.detect(root = k, excludes = ex(k)) is LatexWorkspaceKind.Knowledge) shouldBe true
                WorkspaceLatexRun.detect(root = e, excludes = ex(e)) shouldBe LatexWorkspaceKind.Engineering
            } finally {
                k.deleteRecursively()
                e.deleteRecursively()
            }
        }

        test("shown() replaces control characters").config(timeout = 5.seconds) {
            LatexRunReport.shown("a\u001B[31mb\nc") shouldBe "a [31mb c"
        }

        test("knowledge: prepare, blocksStrict (NOTE never blocks), write, then refuse, then overwrite").config(timeout = 400.seconds) {
            val root = knowledge()
            try {
                val target = File(root, "out")
                val prepared = WorkspaceLatexRun.prepare(root = root, target = target, themeName = null)
                (prepared is PreparedLatexRun.Knowledge) shouldBe true
                prepared.hasNothingToWrite() shouldBe false
                WorkspaceLatexRun.blocksStrict(prepared) shouldBe true // WARN lines exist
                LatexRunReport.issueLines(prepared).any { it.level == "NOTE" } shouldBe true
                WorkspaceLatexRun.existingTargets(prepared = prepared, outDir = target).shouldBeEmpty()
                val result = WorkspaceLatexRun.write(prepared = prepared, outDir = target, overwrite = false)
                File(target, "main.tex").isFile shouldBe true
                val summary = LatexRunReport.summaryLines(prepared = prepared, result = result)
                summary[0] shouldContain "Wrote main.tex, kuml-preamble.tex, 4 figure(s)"
                summary[1] shouldBe "0 failed, 0 skipped, 0 excluded from main.tex, 1 warnings"
                WorkspaceLatexRun.existingTargets(prepared = prepared, outDir = target).shouldNotBeEmpty()
                val refused =
                    shouldThrow<LatexFolderExportException> {
                        WorkspaceLatexRun.write(prepared = prepared, outDir = target, overwrite = false)
                    }
                refused.code shouldBe LatexFolderExportException.Code.OUTPUT_EXISTS
                WorkspaceLatexRun.write(prepared = prepared, outDir = target, overwrite = true)
            } finally {
                root.deleteRecursively()
            }
        }

        test("engineering: failures block strict, a clean run does not, existing files are reported").config(timeout = 400.seconds) {
            val root = engineering(broken = true)
            val clean = engineering()
            try {
                val bad = WorkspaceLatexRun.prepare(root = root, target = File(root, "out"), themeName = null)
                WorkspaceLatexRun.blocksStrict(bad) shouldBe true
                LatexRunReport.counts(bad).failed shouldBe 1
                LatexRunReport.counts(bad).warnings shouldBe null
                LatexRunReport.issueLines(bad).last().text shouldContain "ERROR broken.kuml.kts:"

                val ok = WorkspaceLatexRun.prepare(root = clean, target = File(clean, "out"), themeName = null)
                WorkspaceLatexRun.blocksStrict(ok) shouldBe false
                val out = File(clean, "out")
                WorkspaceLatexRun.existingTargets(prepared = ok, outDir = out).shouldBeEmpty()
                val result = WorkspaceLatexRun.write(prepared = ok, outDir = out, overwrite = false)
                LatexRunReport.summaryLines(prepared = ok, result = result)[0] shouldContain "Wrote 1 figure(s) plus kuml-preamble.tex"
                WorkspaceLatexRun.existingTargets(prepared = ok, outDir = out).toSet() shouldBe
                    setOf("a.tex", "kuml-preamble.tex", "main.tex")
                shouldThrow<LatexFolderExportException> {
                    WorkspaceLatexRun.write(prepared = ok, outDir = out, overwrite = false)
                }.code shouldBe LatexFolderExportException.Code.OUTPUT_EXISTS
                WorkspaceLatexRun.write(prepared = ok, outDir = out, overwrite = true)
                File(out, "main.tex").readText() shouldNotContain "\u0000"
            } finally {
                root.deleteRecursively()
                clean.deleteRecursively()
            }
        }

        test("engineering: a folder with only a broken script has nothing to write").config(timeout = 400.seconds) {
            val root = Files.createTempDirectory("kuml-run-n").toFile()
            try {
                File(root, "broken.kuml.kts").writeText("this is not kotlin (")
                val canon = WorkspaceScanner.canonicalRoot(root)
                val p = WorkspaceLatexRun.prepare(root = canon, target = File(canon, "out"), themeName = null)
                p.hasNothingToWrite() shouldBe true
                LatexRunReport.counts(p).failed shouldBe 1
            } finally {
                root.deleteRecursively()
            }
        }
    })
