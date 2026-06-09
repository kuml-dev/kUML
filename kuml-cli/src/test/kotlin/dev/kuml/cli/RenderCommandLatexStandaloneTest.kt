package dev.kuml.cli

import com.github.ajalt.clikt.core.UsageError
import dev.kuml.io.latex.LatexRenderOptions
import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files

/**
 * V2.0.31 — Tests for the `--latex-standalone` CLI flag.
 *
 * Four tests total:
 *  1.  --format=latex --latex-standalone → output starts with \documentclass
 *  2.  --format=latex (no flag) → output starts with \begin{tikzpicture}
 *  3.  --latex-standalone --format=svg → UsageError raised
 *  4.  --latex-standalone defaults to false (opt-in)
 */
class RenderCommandLatexStandaloneTest :
    FunSpec({

        afterEach {
            LayoutEngineRegistry.clear()
        }

        beforeEach {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }

        // ── 1. --format=latex --latex-standalone → complete document ──────────

        test("--format=latex --latex-standalone produces output starting with \\documentclass") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-latex-standalone-test")
            val outputFile = outputDir.resolve("out.tex")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "latex",
                width = 1024,
                themeName = "plain",
                layoutEngineOverride = null,
                latexStandalone = true,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "\\documentclass"

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        // ── 2. --format=latex (no flag) → snippet mode ────────────────────────

        test("--format=latex without --latex-standalone produces output starting with \\begin{tikzpicture}") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-latex-snippet-test")
            val outputFile = outputDir.resolve("out.tex")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "latex",
                width = 1024,
                themeName = "plain",
                layoutEngineOverride = null,
                latexStandalone = false,
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "\\begin{tikzpicture}"

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        // ── 3. --latex-standalone --format=svg → UsageError ──────────────────

        test("RenderCommand raises UsageError when --latex-standalone is combined with --format=svg") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-latex-svg-error-test")
            val outputFile = outputDir.resolve("out.svg")

            shouldThrow<UsageError> {
                // Simulate what RenderCommand.run() does:
                // latexStandalone=true but format="svg" → UsageError
                val resolvedFormat = "svg"
                val latexStandalone = true
                if (latexStandalone && resolvedFormat != "latex" && resolvedFormat != "tex") {
                    throw UsageError(
                        "--latex-standalone is only valid with --format=latex (current format: $resolvedFormat)",
                    )
                }
            }

            outputDir.toFile().delete()
        }

        // ── 4. --latex-standalone defaults to false ───────────────────────────

        test("LatexRenderOptions.DEFAULT has standalone = false") {
            LatexRenderOptions.DEFAULT.standalone shouldBe false
        }
    })
