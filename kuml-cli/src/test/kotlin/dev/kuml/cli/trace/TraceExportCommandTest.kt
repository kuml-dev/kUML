package dev.kuml.cli.trace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

class TraceExportCommandTest :
    FunSpec({

        val script = File("src/test/resources/simulate/order-lifecycle.kuml.kts")
        val events = File("src/test/resources/simulate/order-lifecycle.events.json")

        /** Generate a recorded trace file for use in export tests. */
        fun generateTrace(): File {
            val traceOut = Files.createTempFile("kuml-trace-export-", ".json").toFile()
            KumlCli().test(
                listOf(
                    "simulate",
                    script.absolutePath,
                    events.absolutePath,
                    "--out",
                    traceOut.absolutePath,
                    "--epoch-clock",
                ),
            )
            return traceOut
        }

        test("exports OTLP JSON to a file when --output is specified") {
            val traceFile = generateTrace()
            val outputFile = Files.createTempFile("kuml-export-", ".otlp.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf("trace", "export", traceFile.absolutePath, "--output", outputFile.absolutePath),
                    )
                result.statusCode shouldBe 0
                val content = outputFile.readText()
                content shouldContain "resourceSpans"
                content shouldContain "kuml.runtime"
            } finally {
                traceFile.delete()
                outputFile.delete()
            }
        }

        test("writes OTLP JSON to stdout when no --output given") {
            val traceFile = generateTrace()
            try {
                val result =
                    KumlCli().test(
                        listOf("trace", "export", traceFile.absolutePath),
                    )
                result.statusCode shouldBe 0
                result.output shouldContain "resourceSpans"
            } finally {
                traceFile.delete()
            }
        }

        test("--service-name is reflected in resource attributes of the export") {
            val traceFile = generateTrace()
            val outputFile = Files.createTempFile("kuml-export-svc-", ".otlp.json").toFile()
            try {
                val result =
                    KumlCli().test(
                        listOf(
                            "trace",
                            "export",
                            traceFile.absolutePath,
                            "--service-name",
                            "my-custom-service",
                            "--output",
                            outputFile.absolutePath,
                        ),
                    )
                result.statusCode shouldBe 0
                val content = outputFile.readText()
                content shouldContain "my-custom-service"
            } finally {
                traceFile.delete()
                outputFile.delete()
            }
        }
    })
