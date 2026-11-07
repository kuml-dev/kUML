package dev.kuml.cli.export

import dev.kuml.cli.Pdflatex
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Builds the real folder export with pdflatex. Without a TeX installation the test is skipped (marker in its name). */
class LatexFolderPdfBuildTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        test("the exported fixture folder builds into one PDF (skipped when TeX is not installed)")
            .config(enabled = Pdflatex.enabled, timeout = 300.seconds) {
                val pdflatex = Pdflatex.requireExecutable()
                val root = Files.createTempDirectory("kuml-folder-pdf").toFile()
                try {
                    File("src/test/resources/latex").copyRecursively(root)
                    val out = File(root, "out")
                    val prepared = LatexFolderExport.prepare(root = root, excludeDirs = setOf(out), themeName = null)
                    prepared.failures shouldBe emptyList()
                    LatexFolderExport.write(prepared = prepared, outDir = out, overwrite = false)
                    prepared.figures
                        .filter { it.excludedReason != null }
                        .map { it.source } shouldBe listOf("bpmn-collaboration.kuml.kts")

                    val process =
                        ProcessBuilder(
                            pdflatex.path,
                            "-interaction=nonstopmode",
                            "-halt-on-error",
                            "-no-shell-escape",
                            "main.tex",
                        ).directory(out)
                            .redirectErrorStream(true)
                            .start()
                    process.outputStream.close()
                    val log = process.inputStream.bufferedReader().use { it.readText() }
                    if (!process.waitFor(120, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        error("pdflatex timed out")
                    }
                    check(process.exitValue() == 0) { "pdflatex failed:\n${log.takeLast(3000)}" }
                    File(out, "main.pdf").isFile shouldBe true
                    log shouldNotContain "Undefined control sequence"
                    log shouldNotContain "write18 enabled"
                    log shouldContain "Output written on main.pdf"
                } finally {
                    root.deleteRecursively()
                }
            }
    })
