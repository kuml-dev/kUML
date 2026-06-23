package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Integration tests for `kuml validate --check-stereotypes`.
 *
 * AP-6.2: Verifies that the new opt-in flag runs [dev.kuml.core.ocl.StereotypeValidator]
 * and merges its violations with model OCL violations.
 */
class ValidateCommandStereotypeTest :
    FunSpec({

        // ── Test 1: No stereotypes → exit 0, only model OCL output ──────────────

        test("validate with --check-stereotypes on model without stereotypes exits 0") {
            val fixture = File("src/test/resources/minimal.kuml.kts")

            val result = KumlCli().test("validate ${fixture.absolutePath} --check-stereotypes")
            result.statusCode shouldBe 0
            result.output shouldContain "valid"
        }

        // ── Test 2: Missing required tag → exit 5, violation names in output ────

        test("validate with --check-stereotypes reports missing required tag with exit 5") {
            val fixture = File("src/test/resources/stereotype-missing-required.kuml.kts")

            val result = KumlCli().test("validate ${fixture.absolutePath} --check-stereotypes")
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            // Must mention the stereotype name and property name
            result.output shouldContain "Entity"
            result.output shouldContain "tableName"
        }

        // ── Test 3: Without flag, stereotype violations are ignored (backward compat) ──

        test("validate without --check-stereotypes ignores stereotype violations (backward compat)") {
            val fixture = File("src/test/resources/stereotype-missing-required.kuml.kts")

            val result = KumlCli().test("validate ${fixture.absolutePath}")
            // Without --check-stereotypes, stereotype violations must NOT appear
            result.statusCode shouldBe 0
            result.output shouldNotContain "tableName"
        }
    })
