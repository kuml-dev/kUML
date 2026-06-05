package dev.kuml.cli

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class SimulateCommandCliTest :
    FunSpec({

        val script = File("src/test/resources/simulate/order-lifecycle.kuml.kts")
        val events = File("src/test/resources/simulate/order-lifecycle.events.json")

        test("kuml simulate writes a trace JSON file") {
            val out = Files.createTempFile("kuml-simulate-", ".trace.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        "simulate ${script.absolutePath} ${events.absolutePath} " +
                            "--out ${out.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0
                val text = out.readText()
                // The schema field is omitted when it matches the default
                // (encodeDefaults = false) — that's fine for the roundtrip.
                text shouldContain "EventReceived"
                text shouldContain "Terminated"
                text shouldContain "modelId"
            } finally {
                out.delete()
            }
        }

        test("--expected with matching trace exits 0") {
            val gold = Files.createTempFile("kuml-simulate-gold-", ".trace.json").toFile()
            try {
                KumlCli().test(
                    "simulate ${script.absolutePath} ${events.absolutePath} " +
                        "--out ${gold.absolutePath} --epoch-clock",
                )
                val result =
                    KumlCli().test(
                        "simulate ${script.absolutePath} ${events.absolutePath} " +
                            "--expected ${gold.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe 0
            } finally {
                gold.delete()
            }
        }

        test("--expected with diverging trace exits with TRACE_DIFF") {
            val divergent = Files.createTempFile("kuml-simulate-bad-", ".trace.json").toFile()
            try {
                divergent.writeText("""{"schema":"kuml.trace.v1","entries":[]}""")
                val result =
                    KumlCli().test(
                        "simulate ${script.absolutePath} ${events.absolutePath} " +
                            "--expected ${divergent.absolutePath} --epoch-clock",
                    )
                result.statusCode shouldBe ExitCodes.TRACE_DIFF
            } finally {
                divergent.delete()
            }
        }
    })
