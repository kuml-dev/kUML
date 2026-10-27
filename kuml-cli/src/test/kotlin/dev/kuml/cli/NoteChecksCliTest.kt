package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
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
