package dev.kuml.desktop.workspace

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The Desktop dialog must reach the LaTeX export only through `WorkspaceLatexRun` / `LatexRunReport` (the code the
 * CLI uses). Calling the lower layers directly, or re-typing the CLI's report lines, would let the two drift apart.
 */
class WorkspaceLatexNoDuplicateLogicTest :
    FunSpec({
        val sourceRoot: File =
            listOf(
                File(System.getProperty("user.dir"), "src/jvmMain/kotlin/dev/kuml/desktop"),
                File(System.getProperty("user.dir"), "kuml-desktop/src/jvmMain/kotlin/dev/kuml/desktop"),
            ).firstOrNull { it.isDirectory } ?: error("desktop sources not found relative to user.dir=${System.getProperty("user.dir")}")

        val sources = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        test("the scan sees the desktop sources and the dialog") {
            (sources.size > 50) shouldBe true
            sources.any { it.name == "WorkspaceLatexExportDialog.kt" } shouldBe true
        }

        test("no desktop code calls the lower LaTeX export layers directly") {
            val forbidden =
                listOf("LatexFolderExport.prepare", "LatexFolderExport.write", "WorkspaceLatexExport.prepare", "WorkspaceLatexExport.write")
            val hits =
                sources.flatMap { f ->
                    val text = f.readText()
                    forbidden.filter { it in text }.map { "${f.name} uses $it" }
                }
            withClue(hits.joinToString("\n")) { hits.shouldBeEmpty() }
        }

        test("no desktop code re-types the CLI's report lines") {
            val forbidden = listOf("excluded from main.tex", "\" failed,", "nothing written")
            val hits =
                sources
                    .filter { it.name != "Strings.kt" }
                    .flatMap { f ->
                        val text = f.readText()
                        forbidden.filter { it in text }.map { "${f.name} contains \"$it\"" }
                    }
            withClue(hits.joinToString("\n")) { hits.shouldBeEmpty() }
        }
    })
