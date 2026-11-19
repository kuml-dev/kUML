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

        test("validate warns about a join with two outgoing flows (ACT318), --strict keeps exit 0").config(timeout = 120.seconds) {
            val join = File("src/test/resources/act/join-two-outgoing.kuml.kts")
            val result = KumlCli().test(listOf("validate", join.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT318"
            KumlCli().test(listOf("validate", join.absolutePath, "--strict")).statusCode shouldBe 0
        }

        test("validate warns about an action with two outgoing flows (ACT321), --strict keeps exit 0").config(timeout = 120.seconds) {
            val action = File("src/test/resources/act/action-two-outgoing.kuml.kts")
            val result = KumlCli().test(listOf("validate", action.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT321"
            KumlCli().test(listOf("validate", action.absolutePath, "--strict")).statusCode shouldBe 0
        }

        test("validate reports ACT322 for initial-incoming, --strict keeps exit 0").config(timeout = 120.seconds) {
            val file = File("src/test/resources/act/initial-incoming.kuml.kts")
            val result = KumlCli().test(listOf("validate", file.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT322"
            KumlCli().test(listOf("validate", file.absolutePath, "--strict")).statusCode shouldBe 0
        }

        test("validate reports ACT323 for final-outgoing, --strict keeps exit 0").config(timeout = 120.seconds) {
            val file = File("src/test/resources/act/final-outgoing.kuml.kts")
            val result = KumlCli().test(listOf("validate", file.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT323"
            KumlCli().test(listOf("validate", file.absolutePath, "--strict")).statusCode shouldBe 0
        }

        test("validate reports ACT324 for callbehavior-two-outgoing, --strict keeps exit 0").config(timeout = 120.seconds) {
            val file = File("src/test/resources/act/callbehavior-two-outgoing.kuml.kts")
            val result = KumlCli().test(listOf("validate", file.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT324"
            KumlCli().test(listOf("validate", file.absolutePath, "--strict")).statusCode shouldBe 0
        }

        test("validate reports ACT325 for decision-one-outgoing, --strict keeps exit 0").config(timeout = 120.seconds) {
            val file = File("src/test/resources/act/decision-one-outgoing.kuml.kts")
            val result = KumlCli().test(listOf("validate", file.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "ACT325"
            KumlCli().test(listOf("validate", file.absolutePath, "--strict")).statusCode shouldBe 0
        }
    })
