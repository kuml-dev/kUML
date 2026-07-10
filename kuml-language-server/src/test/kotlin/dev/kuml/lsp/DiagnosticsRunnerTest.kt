package dev.kuml.lsp

import dev.kuml.langsupport.diagnostics.KumlDiagnostic
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class DiagnosticsRunnerTest :
    FunSpec({

        test("happy path: parses a single TSV diagnostic emitted by the CLI") {
            val stub = FakeCli.write(listOf("ERROR\t3\t5\t3\t9\tUnresolved reference: bar")) ?: return@test
            try {
                DiagnosticsRunner.run("class Foo", stub, 30_000) shouldBe
                    listOf(
                        KumlDiagnostic(
                            message = "Unresolved reference: bar",
                            startLine = 3,
                            startCol = 5,
                            endLine = 3,
                            endCol = 9,
                            severity = KumlDiagnostic.Severity.ERROR,
                        ),
                    )
            } finally {
                stub.parentFile.deleteRecursively()
            }
        }

        test("valid file: stub emits nothing, run returns an empty list") {
            val stub = FakeCli.write(emptyList()) ?: return@test
            try {
                DiagnosticsRunner.run("class Foo", stub, 30_000).shouldBeEmpty()
            } finally {
                stub.parentFile.deleteRecursively()
            }
        }

        test("bogus CLI path returns an empty list and never throws") {
            DiagnosticsRunner.run("class Foo", File("/no/such/kuml"), 30_000).shouldBeEmpty()
        }

        test("timeout: destroys the process and returns empty within a couple seconds") {
            val stub = FakeCli.write(emptyList(), sleepMs = 5_000) ?: return@test
            try {
                val start = System.currentTimeMillis()
                val result = DiagnosticsRunner.run("class Foo", stub, 200)
                val elapsed = System.currentTimeMillis() - start

                result.shouldBeEmpty()
                (elapsed < 4_000) shouldBe true
            } finally {
                stub.parentFile.deleteRecursively()
            }
        }
    })
