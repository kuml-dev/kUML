package dev.kuml.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

        test("RenderPipeline renders deployment-diagram with nested nodes and artifacts") {
            // Regression guard for the "children/artifacts not rendered" bug:
            // UmlNode.children + UmlNode.artifacts must produce LayoutGroups /
            // LayoutNodes so the SVG renderer can draw them.
            val fixture = File("src/test/resources/deployment-nested.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-deploy-nested-test")
            val outputFile = outputDir.resolve("deployment-nested.svg")
            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )
            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            // The outer 3D cube frame (EKS Cluster compound group) must appear.
            content shouldContain "class=\"kuml-node\""
            // Artifact boxes (orderservice.jar, config.yaml, orders.db) must appear.
            content shouldContain "class=\"kuml-artifact\""
            // All node/artifact labels must be present.
            content shouldContain "EKS Cluster"
            content shouldContain "Pod: order-service"
            content shouldContain "orderservice.jar"
            content shouldContain "orders.db"
            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline renders V1.1 component-diagram script with port-qualified connectors") {
            // Regression guard for the UmlLayoutBridge connector port-ID split fix:
            // ComponentDsl.connect(port1, port2) writes "<componentId>::<portName>" into
            // UmlConnector.endNId — the bridge must split those into EndpointRef(nodeId, portId)
            // so the Grid engine can resolve the source/target nodes without throwing.
            val fixture = File("src/test/resources/component-architecture.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-component-test")
            val outputFile = outputDir.resolve("component-architecture.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<?xml"
            content shouldContain "OrderService"
            content shouldContain "MessageBroker"

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        // ── Blueprint integration tests (V3.1.24) ──────────────────────────────

        test("RenderPipeline writes SVG file from Blueprint/Journey-Map script") {
            val fixture = File("src/test/resources/minimal-blueprint.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-blueprint-test")
            val outputFile = outputDir.resolve("minimal-blueprint.svg")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "svg",
                width = 1024,
                themeName = "plain",
            )

            val content = outputFile.toFile().readText()
            content shouldStartWith "<svg"
            content shouldContain "Entdeckung"
            content shouldContain "Interesse"
            content shouldContain "Sieht Post"
            // arrowhead marker must appear exactly once (not per-connection)
            val defsCount = Regex("""id="bp-arrow"""").findAll(content).count()
            defsCount shouldBe 1

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline writes PNG file from Blueprint/Journey-Map script") {
            val fixture = File("src/test/resources/minimal-blueprint.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-blueprint-png-test")
            val outputFile = outputDir.resolve("minimal-blueprint.png")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "png",
                width = 1024,
                themeName = "plain",
            )

            val bytes = outputFile.toFile().readBytes()
            bytes.size shouldNotBe 0
            check(bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) {
                "Output is not a PNG (first bytes: ${bytes.take(8).joinToString { "%02x".format(it) }})"
            }

            outputFile.toFile().delete()
            outputDir.toFile().delete()
        }

        test("RenderPipeline writes LaTeX/TikZ file from Blueprint/Journey-Map script (V3.1.26)") {
            val fixture = File("src/test/resources/minimal-blueprint.kuml.kts")
            val outputDir = Files.createTempDirectory("kuml-blueprint-latex-test")
            val outputFile = outputDir.resolve("minimal-blueprint.tex")

            RenderPipeline.run(
                input = fixture,
                output = outputFile,
                format = "latex",
                width = 1024,
                themeName = "plain",
            )

            val content = outputFile.toFile().readText()
            content shouldContain """\begin{tikzpicture}"""
            content shouldContain """\end{tikzpicture}"""

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
