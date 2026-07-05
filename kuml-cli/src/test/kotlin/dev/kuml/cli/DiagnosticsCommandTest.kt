package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class DiagnosticsCommandTest :
    FunSpec({

        test("valid script emits no diagnostics and exits 0") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val result = KumlCli().test(listOf("diagnostics", fixture.absolutePath))
            result.statusCode shouldBe 0
            result.output
                .lineSequence()
                .filter { it.isNotBlank() }
                .count() shouldBe 0
        }

        test("broken script emits error diagnostics and still exits 0") {
            val fixture = File("src/test/resources/broken-unresolved.kuml.kts")
            val result = KumlCli().test(listOf("diagnostics", fixture.absolutePath))

            // Exit 0: validity is conveyed in the payload, not the exit code.
            result.statusCode shouldBe 0

            // The exact tab-separated field layout (severity, line, col, message) is
            // verified by the plugin's KumlCliDiagnosticsTest.parse; clikt's test
            // terminal collapses tabs, so here we assert on content instead.
            val lines =
                result.output
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
            lines.size shouldBeGreaterThanOrEqual 1
            result.output shouldContain "ERROR"
            result.output shouldContain "Unresolved reference"
        }
    })
