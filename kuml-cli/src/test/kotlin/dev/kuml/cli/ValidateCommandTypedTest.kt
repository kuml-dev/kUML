package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * V2.0.20b — CLI integration tests for the unified `kuml validate` command
 * with expression validation and `--strict` flag.
 *
 * These tests exercise the new expression-validation path added in V2.0.20b:
 *  - `kuml validate <script>` → exits 0 for valid scripts (UML, SysML2, PAR)
 *  - `kuml validate --strict <script>` → exits 0 for clean scripts, non-zero
 *    when expression parse failures or constraint type errors are found
 */
class ValidateCommandTypedTest :
    FunSpec({

        // ── 1. Thermostat STM without --strict → exit 0 ───────────────────────

        test("validate thermostat-stm exits 0 without --strict") {
            val fixture =
                File("src/test/resources/simulate/sysml2/pepela/thermostat-stm.kuml.kts")
            val result = KumlCli().test("validate ${fixture.absolutePath}")
            result.statusCode shouldBe 0
        }

        // ── 2. Thermostat STM with --strict → exit 0 ─────────────────────────

        test("validate --strict thermostat-stm exits 0 (all expressions parse cleanly)") {
            val fixture =
                File("src/test/resources/simulate/sysml2/pepela/thermostat-stm.kuml.kts")
            val result = KumlCli().test("validate --strict ${fixture.absolutePath}")
            result.statusCode shouldBe 0
        }

        // ── 3. Broken-guard without --strict → exit 0 (warnings only) ────────

        test("validate broken-guard without --strict exits 0 (warnings only, not strict)") {
            val fixture =
                File("src/test/resources/validate-expressions/broken-guard.kuml.kts")
            val result = KumlCli().test("validate ${fixture.absolutePath}")
            result.statusCode shouldBe 0
        }

        // ── 4. Broken-guard with --strict → exit non-zero ─────────────────────

        test("validate --strict broken-guard exits non-zero") {
            val fixture =
                File("src/test/resources/validate-expressions/broken-guard.kuml.kts")
            val result = KumlCli().test("validate --strict ${fixture.absolutePath}")
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
        }

        // ── 5. Newton PAR without --strict → exit 0 ──────────────────────────

        test("validate newton-second-law-par exits 0") {
            val fixture =
                File("src/test/resources/validate/newton-second-law-par.kuml.kts")
            val result = KumlCli().test("validate ${fixture.absolutePath}")
            result.statusCode shouldBe 0
        }

        // ── 6. Newton PAR with --strict → exit 0 (no constraint errors) ──────

        test("validate --strict newton-second-law-par exits 0 (F = m * a type-checks)") {
            val fixture =
                File("src/test/resources/validate/newton-second-law-par.kuml.kts")
            val result = KumlCli().test("validate --strict ${fixture.absolutePath}")
            result.statusCode shouldBe 0
            // The output should mention the constraint was checked
            // (either "0 constraint errors" or no error output at all)
        }

        // ── 7. Existing UML script still passes ───────────────────────────────

        test("validate minimal UML script still exits 0 with --strict") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val result = KumlCli().test("validate --strict ${fixture.absolutePath}")
            result.statusCode shouldBe 0
        }
    })
