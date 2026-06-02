package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files

class RenderPipelineTest :
    FunSpec({

        test("RenderPipeline writes SVG file from minimal script") {
            val fixture = File("src/test/resources/minimal.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-test")
            val outputFile = outputDir.resolve("minimal.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"

            // cleanup
            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }
    })
