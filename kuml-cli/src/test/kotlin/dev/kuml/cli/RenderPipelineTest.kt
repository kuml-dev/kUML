package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files

class RenderPipelineTest :
    FunSpec({

        test("RenderPipeline writes SVG file from minimal UML script") {
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

        test("RenderPipeline writes SVG file from C4 system-context script") {
            val fixture = File("src/test/resources/minimal-c4.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-c4-test")
            val outputFile = outputDir.resolve("minimal-c4.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            // The C4 SoftwareSystem nodes should be rendered as <g> groups.
            content shouldContain "<svg"
            // Locale regression guard: SVG attributes must use '.' as decimal
            // separator, never ',' — Batik (and the spec) reject `translate(58,67,17)`.
            content shouldNotContain Regex("""translate\(\s*-?\d+,\s*-?\d+,\s*-?\d+""").pattern

            // cleanup
            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline writes SVG file from UML 2.x object-diagram script (V1.1)") {
            val fixture = File("src/test/resources/minimal-object.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-obj-test")
            val outputFile = outputDir.resolve("minimal-object.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            // Instance rectangles get the `kuml-instance` class; the UML
            // underline lives on the header text via text-decoration="underline".
            content shouldContain "class=\"kuml-instance\""
            content shouldContain "text-decoration=\"underline\""
            // Locale regression guard from V1.0.1 still in effect here.
            content shouldNotContain Regex("""translate\(\s*-?\d+,\s*-?\d+,\s*-?\d+""").pattern

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline renders V1.1 activity-diagram script") {
            val fixture = File("src/test/resources/minimal-activity.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-activity-test")
            val outputFile = outputDir.resolve("minimal-activity.svg")
            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )
            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            content shouldContain "class=\"kuml-action\""
            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline renders V1.1 deployment-diagram script") {
            val fixture = File("src/test/resources/minimal-deployment.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-deploy-test")
            val outputFile = outputDir.resolve("minimal-deploy.svg")
            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )
            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            content shouldContain "class=\"kuml-node\""
            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline writes PNG file from C4 system-context script") {
            val fixture = File("src/test/resources/minimal-c4.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-c4-png-test")
            val outputFile = outputDir.resolve("minimal-c4.png")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "png",
                width = 1024,
                themeName = "plain",
            )

            val bytes = outputFile.toFile().readBytes()
            // PNG magic: 89 50 4E 47 0D 0A 1A 0A
            bytes.size shouldNotBe 0
            check(bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
                "Output is not a PNG (first bytes: ${bytes.take(8).joinToString { "%02x".format(it) }})"
            }

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }
    })
