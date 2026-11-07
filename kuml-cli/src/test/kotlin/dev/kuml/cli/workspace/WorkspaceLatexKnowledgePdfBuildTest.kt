package dev.kuml.cli.workspace

import dev.kuml.cli.Pdflatex
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.workspace.WorkspaceScanner
import dev.kuml.workspace.latex.LatexKnowledgeOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Builds the real Knowledge export with pdflatex. Without a TeX installation the test is skipped (marker in its name). */
class WorkspaceLatexKnowledgePdfBuildTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        test("the exported Knowledge fixture builds into one PDF (skipped when TeX is not installed)")
            .config(enabled = Pdflatex.enabled, timeout = 300.seconds) {
                val pdflatex = Pdflatex.requireExecutable()
                val root = Files.createTempDirectory("kuml-knowledge-pdf").toFile()
                try {
                    File("src/test/resources/latex-knowledge").copyRecursively(root)
                    val out = File(root, "out")
                    val ws = WorkspaceScanner.scan(root = root)
                    val prepared =
                        WorkspaceLatexExport.prepare(workspace = ws, options = LatexKnowledgeOptions(generator = "test"), themeName = null)
                    prepared.excludedFigureCount shouldBe 1
                    prepared.figureCount shouldBe 3
                    WorkspaceLatexExport.write(prepared = prepared, outDir = out, overwrite = false)
                    var log = ""
                    repeat(2) {
                        val process =
                            ProcessBuilder(pdflatex.path, "-interaction=nonstopmode", "-halt-on-error", "-no-shell-escape", "main.tex")
                                .directory(out)
                                .redirectErrorStream(true)
                                .start()
                        process.outputStream.close()
                        log = process.inputStream.bufferedReader().use { it.readText() }
                        if (!process.waitFor(120, TimeUnit.SECONDS)) {
                            process.destroyForcibly()
                            error("pdflatex timed out")
                        }
                        check(process.exitValue() == 0) { "pdflatex failed:\n${log.takeLast(3000)}" }
                    }
                    File(out, "main.pdf").isFile shouldBe true
                    log shouldContain "Output written on main.pdf"
                    log shouldNotContain "Undefined control sequence"
                    log shouldNotContain "runsystem("
                } finally {
                    root.deleteRecursively()
                }
            }
    })
