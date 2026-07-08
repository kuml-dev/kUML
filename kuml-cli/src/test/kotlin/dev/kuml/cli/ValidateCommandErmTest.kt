package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * V3.4.1 — CLI smoke tests proving `kuml validate` compiles, extracts, and
 * structurally validates ERM (`ermModel { … }`) scripts via
 * [dev.kuml.erm.constraint.ErmConstraintChecker], and that `kuml render`
 * rejects ERM scripts with the V3.4.2 "not yet supported" stub instead of
 * crashing.
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

        test("render rejects an ERM script with a structured not-yet-supported error (exit 3, not a crash)") {
            val outputFile = File.createTempFile("kuml-erm-render-", ".svg")
            try {
                val result =
                    KumlCli().test(
                        listOf("render", validFixture.absolutePath, "--format", "svg", "--output", outputFile.absolutePath),
                    )
                result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            } finally {
                outputFile.delete()
            }
        }
    })
