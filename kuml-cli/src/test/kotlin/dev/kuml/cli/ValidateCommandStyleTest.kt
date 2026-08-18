package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Integration tests for the `kuml validate` source-style check
 * (`dev.kuml.core.script.style.NamedArgumentStyleCheck`, V0.50.0) — the
 * `RequireNamedArguments` detekt rule (`:kuml-detekt-rules`) ported to run
 * against real script source text through a child-process worker
 * (`:kuml-style-worker`).
 */
class ValidateCommandStyleTest :
    FunSpec({

        val positionalFixture = File("src/test/resources/style/positional.kuml.kts")
        val namedFixture = File("src/test/resources/style/named.kuml.kts")
        val exemptionsFixture = File("src/test/resources/style/exemptions.kuml.kts")
        val combinedFixture = File("src/test/resources/style/combined-ocl-and-style.kuml.kts")

        test("a positional dev.kuml.* argument is rejected with exit code 5") {
            val result = KumlCli().test(listOf("validate", positionalFixture.absolutePath))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "Style validation:"
            result.output shouldContain "POSITIONAL_ARGUMENT"
            result.output shouldContain "classOf"
        }

        test("a positional dev.kuml.* argument is reported with category \"style\" in --output json") {
            val result = KumlCli().test(listOf("validate", positionalFixture.absolutePath, "--output", "json"))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "\"valid\": false"
            result.output shouldContain "\"category\": \"style\""
            result.output shouldContain "\"id\": \"POSITIONAL_ARGUMENT\""
        }

        test("a fully named script passes the style check and exits 0") {
            val result = KumlCli().test(listOf("validate", namedFixture.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldNotContain "Style validation:"
        }

        test("single-value-parameter and block-DSL-lambda calls remain exempt") {
            val result = KumlCli().test(listOf("validate", exemptionsFixture.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldNotContain "Style validation:"
        }

        test("--no-check-style skips the check even on a positional-argument script") {
            val result = KumlCli().test(listOf("validate", positionalFixture.absolutePath, "--no-check-style"))
            result.statusCode shouldBe 0
            result.output shouldNotContain "Style validation:"
        }

        test("a script with both a genuine OCL violation and a style violation reports both") {
            val result = KumlCli().test(listOf("validate", combinedFixture.absolutePath))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            // The OCL violation (constraint "hasAttr" fails on an empty class):
            result.output shouldContain "Model OCL violations:"
            result.output shouldContain "hasAttr"
            // The style violation (constraint()'s own arguments passed positionally):
            result.output shouldContain "Style validation:"
            result.output shouldContain "POSITIONAL_ARGUMENT"
        }

        test("a script with both violation kinds reports both in --output json") {
            val result = KumlCli().test(listOf("validate", combinedFixture.absolutePath, "--output", "json"))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "\"valid\": false"
            result.output shouldContain "\"model\""
            result.output shouldContain "\"structural\""
            result.output shouldContain "\"category\": \"style\""
        }
    })
