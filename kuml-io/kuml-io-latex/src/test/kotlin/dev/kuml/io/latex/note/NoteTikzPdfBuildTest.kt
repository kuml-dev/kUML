package dev.kuml.io.latex.note

import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val PDFLATEX: File? =
    (System.getenv("PATH").orEmpty().split(File.pathSeparator) + "/Library/TeX/texbin" + "/usr/bin" + "/usr/local/bin")
        .map { File(it, "pdflatex") }
        .firstOrNull { it.canExecute() }

/** Compiles [tex] with pdflatex (no shell escape, no shell, 60 s limit) and returns the log; fails on a non-zero exit. */
private fun compile(
    name: String,
    tex: String,
): String {
    val dir = Files.createTempDirectory("kuml-note-pdf-").toFile()
    try {
        File(dir, "$name.tex").writeText(tex)
        val process =
            ProcessBuilder(PDFLATEX!!.path, "-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", "$name.tex")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("pdflatex timed out for $name")
        }
        check(process.exitValue() == 0) { "pdflatex failed for $name:\n${output.takeLast(2000)}" }
        File(dir, "$name.pdf").exists() shouldBe true
        return output
    } finally {
        dir.deleteRecursively()
    }
}

private const val MARKUP_BODY =
    "**Bold** and *italic* and `code` and [a link](https://example.org) with # \$ % & _ { } ~ ^ \\\n" +
        "- first item\n- second item\n1. one\n2. two"

/**
 * Builds real PDFs from the note output. Without a TeX installation the tests are skipped
 * (the marker in the test name lets CI tell a skip from a run).
 */
class NoteTikzPdfBuildTest :
    StringSpec({
        val tex = PDFLATEX != null

        "UML notes of every kind compile (skipped when TeX is not installed)".config(enabled = tex) {
            for (kind in NoteKind.entries) {
                val log =
                    compile(
                        name = "uml-${kind.name.lowercase()}",
                        tex = NoteFixtures.renderUml(body = MARKUP_BODY, kind = kind, standalone = true),
                    )
                log shouldNotContain "Undefined control sequence"
                log shouldNotContain "shell escape"
            }
        }

        "SysML 2 BDD, STM and C4 notes compile (skipped when TeX is not installed)".config(enabled = tex) {
            val docs =
                mapOf(
                    "bdd" to NoteFixtures.renderBdd(body = MARKUP_BODY, kind = NoteKind.INFO, standalone = true),
                    "stm" to NoteFixtures.renderStm(body = MARKUP_BODY, kind = NoteKind.RISK, standalone = true),
                    "c4" to NoteFixtures.renderC4(body = MARKUP_BODY, kind = NoteKind.HINT, standalone = true),
                )
            for ((name, doc) in docs) {
                val log = compile(name = name, tex = doc)
                log shouldNotContain "Undefined control sequence"
                log shouldNotContain "shell escape"
            }
        }

        "hostile note texts compile as plain text and run nothing (skipped when TeX is not installed)".config(enabled = tex) {
            var n = 0
            for (hostile in HOSTILE_TEXTS) {
                val docs =
                    listOf(
                        NoteFixtures.renderUml(body = hostile, kind = NoteKind.NOTE, standalone = true),
                        NoteFixtures.renderBdd(body = "**$hostile**", kind = NoteKind.DEPRECATED, standalone = true),
                        NoteFixtures.renderC4(body = "[$hostile](https://a.example)", kind = NoteKind.ERROR, standalone = true),
                    )
                for (doc in docs) {
                    val log = compile(name = "hostile-${n++}", tex = doc)
                    log shouldNotContain "Undefined control sequence"
                    log shouldNotContain "write18 enabled"
                }
            }
        }
    })
