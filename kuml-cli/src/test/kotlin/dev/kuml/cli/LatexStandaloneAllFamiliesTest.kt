package dev.kuml.cli

import dev.kuml.layout.LayoutEngineRegistry
import dev.kuml.layout.elk.ElkLayoutEngineProvider
import dev.kuml.layout.grid.GridLayoutEngineProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Nacharbeit 30 — `latexStandalone` must reach every diagram family, not only UML and Blueprint.
 *
 * One fixture per family / diagram kind, all extracted from the vault examples. For each, the pipeline is run
 * in standalone and in snippet mode and the document skeleton is asserted. A second, opt-in test compiles the
 * standalone output with a real `pdflatex` when one is installed; without it the test is disabled and says why.
 */
class LatexStandaloneAllFamiliesTest :
    FunSpec({

        beforeEach {
            LayoutEngineRegistry.clear()
            LayoutEngineRegistry.register(GridLayoutEngineProvider())
            LayoutEngineRegistry.register(ElkLayoutEngineProvider())
        }
        afterEach { LayoutEngineRegistry.clear() }

        fun render(
            name: String,
            standalone: Boolean,
            dir: Path,
        ): String {
            val out = dir.resolve("$name-${if (standalone) "standalone" else "snippet"}.tex")
            RenderPipeline.run(
                input = File("src/test/resources/latex/$name.kuml.kts"),
                output = out,
                format = "latex",
                width = 1024,
                themeName = "kuml",
                latexStandalone = standalone,
            )
            return out.toFile().readText()
        }

        for (family in FAMILIES) {
            test("standalone LaTeX is a complete document for $family") {
                val dir = Files.createTempDirectory("kuml-latex-all")
                try {
                    val tex = render(family, standalone = true, dir = dir)
                    tex shouldStartWith "\\documentclass[border=10pt]{standalone}"
                    tex.split("\\begin{document}").size shouldBe 2
                    tex shouldContain "\\begin{tikzpicture}"
                    tex.trimEnd() shouldEndWith "\\end{document}"
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }

            test("snippet LaTeX carries no document skeleton for $family") {
                val dir = Files.createTempDirectory("kuml-latex-all")
                try {
                    val tex = render(family, standalone = false, dir = dir)
                    tex shouldNotContain "\\documentclass"
                    tex shouldNotContain "\\begin{document}"
                    tex shouldNotContain "\\end{document}"
                    tex shouldContain "\\begin{tikzpicture}"
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }

        val pdflatex = findPdflatex()
        if (pdflatex == null) {
            println("[LatexStandaloneAllFamiliesTest] pdflatex not found on PATH or /Library/TeX/texbin: compile tests are disabled")
        }
        KNOWN_COMPILE_DEFECTS.forEach { (family, reason) ->
            println(
                "[LatexStandaloneAllFamiliesTest] pdflatex test for $family disabled, known renderer defect (not a preamble issue): $reason",
            )
        }
        for (family in FAMILIES) {
            test("pdflatex compiles the standalone document for $family")
                .config(enabled = pdflatex != null && family !in KNOWN_COMPILE_DEFECTS) {
                    val dir = Files.createTempDirectory("kuml-latex-pdflatex")
                    try {
                        val tex = render(family, standalone = true, dir = dir)
                        val file = dir.resolve("$family.tex")
                        Files.writeString(file, tex)
                        val proc =
                            ProcessBuilder(pdflatex, "-interaction=nonstopmode", "-halt-on-error", file.fileName.toString())
                                .directory(dir.toFile())
                                .redirectErrorStream(true)
                                .start()
                        val log = proc.inputStream.bufferedReader().readText()
                        val finished = proc.waitFor(120, TimeUnit.SECONDS)
                        if (!finished) proc.destroyForcibly()
                        check(finished) { "pdflatex timed out for $family" }
                        check(proc.exitValue() == 0) { "pdflatex failed for $family:\n${log.takeLast(2000)}" }
                        check(Files.exists(dir.resolve("$family.pdf"))) { "no PDF produced for $family" }
                        // Optional: keep the PDFs for a visual check (KUML_LATEX_KEEP_DIR=/some/dir).
                        System.getenv("KUML_LATEX_KEEP_DIR")?.takeIf { it.isNotBlank() }?.let { keep ->
                            Files.createDirectories(Path.of(keep))
                            Files.copy(
                                dir.resolve("$family.pdf"),
                                Path.of(keep, "$family.pdf"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                    } finally {
                        dir.toFile().deleteRecursively()
                    }
                }
        }
    }) {
    companion object {
        /**
         * Families whose generated TikZ does not compile for a reason beyond the document preamble. They are
         * reported as findings instead of being fixed in this wave (open question 3 of the plan).
         */
        private val KNOWN_COMPILE_DEFECTS: Map<String, String> =
            mapOf(
                "bpmn-collaboration" to
                    "message flows reference node names such as p_customer_start_1 that the collaboration TikZ never defines " +
                    "(pgf: No shape named `p_customer_start_1')",
            )

        private val FAMILIES =
            listOf(
                "uml-class",
                "uml-sequence",
                "uml-state",
                "c4-context",
                "sysml2-bdd",
                "sysml2-ibd",
                "sysml2-uc",
                "sysml2-req",
                "sysml2-stm",
                "sysml2-act",
                "sysml2-seq",
                "sysml2-par",
                "bpmn-process",
                "bpmn-collaboration",
                "blueprint",
            )

        private fun findPdflatex(): String? {
            val onPath =
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .map { File(it, "pdflatex") }
                    .firstOrNull { it.canExecute() }
            val fallback = File("/Library/TeX/texbin/pdflatex").takeIf { it.canExecute() }
            return (onPath ?: fallback)?.absolutePath
        }
    }
}
