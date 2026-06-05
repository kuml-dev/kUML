package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import com.github.ajalt.clikt.core.main as cliktMain

class CliIntegrationTest :
    FunSpec({

        test("KumlCli render writes PNG via --format png") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-cli-test")
            val outputFile = outputDir.resolve("minimal.png").toAbsolutePath().toString()

            KumlCli().cliktMain(
                arrayOf(
                    "render",
                    fixture.absolutePath,
                    "--format",
                    "png",
                    "--output",
                    outputFile,
                ),
            )

            val bytes = File(outputFile).readBytes()
            // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
            bytes[0] shouldBe 0x89.toByte()
            bytes[1] shouldBe 0x50.toByte()
            bytes[2] shouldBe 0x4E.toByte()
            bytes[3] shouldBe 0x47.toByte()

            // cleanup
            File(outputFile).delete()
            outputDir.toFile().delete()
        }
    })
