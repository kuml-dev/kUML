package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * V2.0.20a — CLI integration tests for `kuml validate-expressions`.
 */
class ValidateExpressionsCommandTest :
    FunSpec({

        test("thermostat-stm: all guards parseable → exit 0") {
            val fixture =
                File("src/test/resources/simulate/sysml2/pepela/thermostat-stm.kuml.kts")
            val result = KumlCli().test(listOf("validate-expressions", fixture.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "parsed"
        }

        test("traffic-light: no guards → exit 0") {
            val fixture =
                File("src/test/resources/simulate/sysml2/traffic-light.kuml.kts")
            val result = KumlCli().test(listOf("validate-expressions", fixture.absolutePath))
            result.statusCode shouldBe 0
        }

        test("broken-guard script → exit non-zero") {
            val fixture =
                File("src/test/resources/validate-expressions/broken-guard.kuml.kts")
            val result = KumlCli().test(listOf("validate-expressions", fixture.absolutePath))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "FAIL"
        }

        test("--json flag produces valid JSON array") {
            val fixture =
                File("src/test/resources/simulate/sysml2/pepela/thermostat-stm.kuml.kts")
            val result = KumlCli().test(listOf("validate-expressions", "--json", fixture.absolutePath))
            result.statusCode shouldBe 0
            result.output.trim().startsWith("[") shouldBe true
            result.output shouldContain "\"parsed\""
            result.output shouldContain "\"expression\""
        }
    })
