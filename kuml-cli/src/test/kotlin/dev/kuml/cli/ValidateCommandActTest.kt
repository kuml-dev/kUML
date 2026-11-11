package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Wave AJ (g) — `kuml validate` reports the ACT316 warning without failing the run. */
class ValidateCommandActTest :
    FunSpec({
        val fixture = File("src/test/resources/act/fork-two-incoming.kuml.kts")

        test("validate warns about a fork with two incoming flows and exits 0").config(timeout = 120.seconds) {
            val result = KumlCli().test(listOf("validate", fixture.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT316"
        }

        test("validate --output json reports ACT316 with warning severity").config(timeout = 120.seconds) {
            val result = KumlCli().test(listOf("validate", fixture.absolutePath, "--output", "json"))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT316"
            result.output shouldContain "\"warning\""
        }
    })
