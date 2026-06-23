package dev.kuml.cli.sandbox

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class SandboxValidateCommandTest :
    FunSpec({
        val cleanScript = File("src/test/resources/sandbox/sandbox-clean.kuml.kts")
        val violationsScript = File("src/test/resources/sandbox/sandbox-violations.kuml.kts")
        val invalidScript = File("src/test/resources/sandbox/sandbox-invalid.kuml.kts")

        test("exit 0 for clean model with default policy") {
            val result = KumlCli().test("sandbox validate ${cleanScript.absolutePath}")
            result.statusCode shouldBe 0
            result.output shouldContain "passed"
        }

        test("exit 12 for violations with strict policy") {
            val result =
                KumlCli().test("sandbox validate ${violationsScript.absolutePath} --strict")
            result.statusCode shouldBe ExitCodes.SANDBOX_VIOLATIONS
            result.output shouldContain "violation"
        }

        test("exit 3 for script with compilation errors") {
            val result = KumlCli().test("sandbox validate ${invalidScript.absolutePath}")
            result.statusCode shouldBe ExitCodes.SCRIPT_ERROR
        }
    })
