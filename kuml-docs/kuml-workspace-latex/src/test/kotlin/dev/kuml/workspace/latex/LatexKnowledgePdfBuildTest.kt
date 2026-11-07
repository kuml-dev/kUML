package dev.kuml.workspace.latex

import dev.kuml.workspace.latex.LatexTestSupport.main
import dev.kuml.workspace.latex.LatexTestSupport.write
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private fun errorExcerpt(log: String): String {
    val lines = log.lines()
    val i = lines.indexOfFirst { it.startsWith("! ") }
    return if (i < 0) log.takeLast(2000) else lines.drop(i).take(12).joinToString("\n")
}

/** Builds hostile content with the real pdflatex. Without a TeX installation the test is skipped (marker in its name). */
class LatexKnowledgePdfBuildTest :
    FunSpec({
        test("a hostile workspace builds into a PDF and runs nothing (skipped when TeX is not installed)")
            .config(enabled = Pdflatex.enabled, timeout = 300.seconds) {
                val pdflatex = Pdflatex.requireExecutable()
                val pwn = File("/tmp/kuml-pwn-${UUID.randomUUID()}")
                val hostile =
                    "\\write18{touch ${pwn.path}} \\input{/etc/passwd} \\begin{document} \\end{document} }}} \\\\[1cm] " +
                        "% \uD83D\uDE00 日本 Привет ^^5c \$x\$ ~ _ & # \"q\" [b] < > |"
                val ws =
                    LatexTestSupport.workspace(
                        files =
                            mapOf(
                                "index.md" to
                                    "---\ntype: KumlWorkspace\ntitle: $hostile\nauthor: $hostile\nlang: de\n---\n# T\n\n- [a](a.md)\n",
                                "a.md" to
                                    LatexTestSupport.doc(
                                        title = hostile,
                                        body =
                                            "$hostile\n\n## $hostile\n\n| $hostile | B |\n|---|---|\n| $hostile | x |\n\n" +
                                                "- $hostile\n    - $hostile\n\n" +
                                                "```text\n$hostile\n\\end{kumlcode}\n```\n\n" +
                                                "[l $hostile](https://example.org/a?b=c&d=e)\n\n" +
                                                "![$hostile](missing.png)\n\n```kuml\nx\n```\n",
                                    ),
                            ),
                    )
                val prepared = LatexTestSupport.prepare(ws = ws)
                val out = LatexTestSupport.tempDir()
                prepared.write(out)
                prepared.main() shouldNotContain "\\write18{touch"
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
                    check(process.exitValue() == 0) { "pdflatex failed in ${out.path}:\n" + errorExcerpt(log) }
                }
                File(out, "main.pdf").isFile shouldBe true
                log shouldNotContain "runsystem("
                log shouldContain "Output written on main.pdf"
                Regex("(?m)^! ").containsMatchIn(log) shouldBe false
                pwn.exists() shouldBe false
            }
    })
