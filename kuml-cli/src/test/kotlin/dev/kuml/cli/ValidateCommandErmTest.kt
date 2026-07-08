package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * V3.4.1/V3.4.2 — CLI smoke tests proving `kuml validate` compiles, extracts,
 * and structurally validates ERM (`ermModel { … }`) scripts via
 * [dev.kuml.erm.constraint.ErmConstraintChecker], and that `kuml render` now
 * renders ERM/Martin diagrams (V3.4.2) instead of the V3.4.1 stub.
 */
class ValidateCommandErmTest :
    FunSpec({

        val validFixture = File("src/test/resources/erm/valid-ecommerce.kuml.kts")
        val brokenFixture = File("src/test/resources/erm/broken-fk.kuml.kts")

        test("validate accepts a valid ERM script and exits 0") {
            val result = KumlCli().test(listOf("validate", validFixture.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "valid"
        }

        test("validate detects a broken foreign key and exits with code 5") {
            val result = KumlCli().test(listOf("validate", brokenFixture.absolutePath))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "ERM validation"
            result.output shouldContain "targets unknown entity"
        }

        test("validate --output json reports the broken ERM script's violation in the structural section") {
            val result = KumlCli().test(listOf("validate", brokenFixture.absolutePath, "--output", "json"))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "\"valid\": false"
            result.output shouldContain "\"structural\""
            result.output shouldContain "targets unknown entity"
        }

        test("render produces an ERM/Martin SVG for a valid ERM script (V3.4.2)") {
            val outputFile = File.createTempFile("kuml-erm-render-", ".svg")
            try {
                val result =
                    KumlCli().test(
                        listOf("render", validFixture.absolutePath, "--format", "svg", "--output", outputFile.absolutePath),
                    )
                result.statusCode shouldBe 0
                val svg = outputFile.readText()
                svg shouldContain "<svg"
                svg shouldContain "Customer"
                svg shouldContain "Order"
            } finally {
                outputFile.delete()
            }
        }

        test("render --notation bachman throws a structured not-yet-supported error (exit 3)") {
            val outputFile = File.createTempFile("kuml-erm-render-bachman-", ".svg")
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "render",
                            validFixture.absolutePath,
                            "--format",
                            "svg",
                            "--output",
                            outputFile.absolutePath,
                            "--notation",
                            "bachman",
                        ),
                    )
                // The structured "not yet supported" message goes to System.err.println
                // (matching RenderCommand's existing convention), not `echo(err = true)`,
                // so Clikt's test() recorder does not capture it — only the exit code is
                // asserted here (see CliktCommandTestResult's KDoc).
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            } finally {
                outputFile.delete()
            }
        }

        test("render --notation garbage is rejected by Clikt's choice validation") {
            val outputFile = File.createTempFile("kuml-erm-render-invalid-", ".svg")
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "render",
                            validFixture.absolutePath,
                            "--format",
                            "svg",
                            "--output",
                            outputFile.absolutePath,
                            "--notation",
                            "garbage",
                        ),
                    )
                result.statusCode shouldNotBe 0
            } finally {
                outputFile.delete()
            }
        }
    })
