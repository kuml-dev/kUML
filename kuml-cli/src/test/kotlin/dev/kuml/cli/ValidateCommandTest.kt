package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

class ValidateCommandTest :
    FunSpec({

        test("validate reports valid for script without constraints") {
            val fixture = File("src/test/resources/minimal.kuml.kts")

            // Should complete without throwing ProgramResult (exit code 0)
            val result = KumlCli().test("validate ${fixture.absolutePath}")
            result.statusCode shouldBe 0
        }

        test("validate detects violation and exits with code 4") {
            val fixture = File("src/test/resources/violated-constraints.kuml.kts")

            val result = KumlCli().test("validate ${fixture.absolutePath}")
            result.statusCode shouldBe 4
        }

        test("validate --output json produces valid JSON") {
            val fixture = File("src/test/resources/violated-constraints.kuml.kts")

            val result = KumlCli().test("validate ${fixture.absolutePath} --output json")
            result.statusCode shouldBe 4
            result.output shouldContain "\"valid\""
            result.output shouldContain "\"violations\""
            result.output shouldContain "\"hasAttr\""
        }
    })
