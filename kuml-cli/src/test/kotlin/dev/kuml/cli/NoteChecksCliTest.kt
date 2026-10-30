package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class NoteChecksCliTest :
    FunSpec({

        fun fixture(name: String) = File("src/test/resources/notes/$name").absolutePath

        test("validate reports NOTE_TRUNCATED as a warning without echoing the body") {
            val result = KumlCli().test(listOf("validate", fixture("sysml2-notes.kuml.kts")))
            result.statusCode shouldBe 0
            result.output shouldContain "Note validation"
            result.output shouldContain "NOTE_TRUNCATED"
            result.output shouldContain "longNote"
            result.output shouldNotContain "long long long"
        }

        test("validate reports an unknown anchor as a KUML-NOTE-W001 warning naming anchor and note, not as a crash") {
            val result = KumlCli().test(listOf("validate", fixture("sysml2-unknown-anchor.kuml.kts")))
            withClue(result.output) { result.statusCode shouldBe 0 }
            result.output shouldContain "KUML-NOTE-W001"
            result.output shouldContain "Anker unbekannt: Gibtsnicht (Notiz ghost)"
            result.output shouldContain "KUML-NOTE-W002"
            result.output shouldContain "Notiz ontransition"
            result.output shouldContain "Diagramm Overview"
            result.output shouldNotContain "SECRET-NOTE-BODY"
        }

        test("render still produces an SVG for an unknown anchor and prints the warning") {
            val out = File.createTempFile("kuml-notes-", ".svg")
            try {
                val result =
                    KumlCli().test(
                        listOf("render", fixture("sysml2-unknown-anchor.kuml.kts"), "--format", "svg", "--output", out.absolutePath),
                    )
                result.statusCode shouldBe 0
                out.readText() shouldContain "<svg"
            } finally {
                out.delete()
            }
        }

        test("C4 notes are drawn with dotted anchor lines (P-COM-04)") {
            val out = File.createTempFile("kuml-c4-notes-", ".svg")
            try {
                val result = KumlCli().test(listOf("render", fixture("c4-notes.kuml.kts"), "--format", "svg", "--output", out.absolutePath))
                withClue(result.output) { result.statusCode shouldBe 0 }
                val svg = out.readText()
                svg shouldContain "kuml-c4-note"
                svg shouldContain "ANNAHME A1: Test"
                svg shouldContain "id=\"twoNote\""
                svg shouldContain "id=\"freeNote\""
                Regex("class=\"kuml-note-anchor\"").findAll(svg).count() shouldBe 3
                File("build/c4-notes-sample.svg").also { it.parentFile.mkdirs() }.writeText(svg)
            } finally {
                out.delete()
            }
        }

        for ((name, marker) in listOf(
            "sysml2-notes.kuml.kts" to "kuml-sysml2-comment",
            "bpmn-notes.kuml.kts" to "kuml-bpmn-text-annotation",
            "erm-notes.kuml.kts" to "kuml-erm-note",
        )) {
            test("render draws notes for $name") {
                val out = File.createTempFile("kuml-notes-", ".svg")
                try {
                    val result = KumlCli().test(listOf("render", fixture(name), "--format", "svg", "--output", out.absolutePath))
                    result.statusCode shouldBe 0
                    out.readText() shouldContain marker
                } finally {
                    out.delete()
                }
            }
        }
    })
