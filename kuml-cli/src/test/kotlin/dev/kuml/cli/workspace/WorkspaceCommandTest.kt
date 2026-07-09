package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class WorkspaceCommandTest :
    FunSpec({

        val demoRoot = File("src/test/resources/okf-workspace")

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-cli").toFile()

        test("workspace info reports mode=knowledge and per-type document counts") {
            val result = KumlCli().test(listOf("workspace", "info", demoRoot.absolutePath, "-o", "json"))
            result.statusCode shouldBe 0
            result.output shouldContain "\"mode\": \"knowledge\""
            result.output shouldContain "\"documentCount\": 5"
            result.output shouldContain "\"UmlClassDiagram\": 1"
            result.output shouldContain "\"UmlStateMachine\": 1"
            result.output shouldContain "\"Concept\": 1"
            result.output shouldContain "\"Article\": 1"
            result.output shouldContain "\"KumlWorkspace\": 1"
        }

        test("workspace validate reports no findings on the clean demo workspace") {
            val result = KumlCli().test(listOf("workspace", "validate", demoRoot.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "valid"
        }

        test("workspace validate reports E-001/E-003/E-005 on a broken fixture and exits 5") {
            val root = tempWorkspace()
            File(root, "no-type.md").writeText("# No frontmatter at all\n")
            File(root, "diagram-without-block.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |# Missing the ```kuml block entirely
                """.trimMargin(),
            )
            File(root, "dead-link.md").writeText(
                """
                |---
                |type: Concept
                |---
                |See [nowhere](does-not-exist.md).
                """.trimMargin(),
            )

            val result = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "-o", "json"))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "OKF-E-001"
            result.output shouldContain "OKF-E-003"
            result.output shouldContain "OKF-E-005"

            root.deleteRecursively()
        }

        test("workspace render --format svg writes one SVG per diagram document") {
            val outDir = File(tempWorkspace(), "out")
            val result =
                KumlCli().test(
                    listOf("workspace", "render", demoRoot.absolutePath, "-o", outDir.absolutePath, "-f", "svg"),
                )
            result.statusCode shouldBe 0
            result.output shouldContain "Rendered 2"

            val classesSvg = File(outDir, "models/domain-classes.svg")
            val stateSvg = File(outDir, "models/checkout-state.svg")
            classesSvg.exists() shouldBe true
            stateSvg.exists() shouldBe true
            classesSvg.readText() shouldContain "<svg"
            stateSvg.readText() shouldContain "<svg"

            outDir.deleteRecursively()
        }

        test("workspace render --mirror replaces kuml blocks with image links") {
            val outDir = File(tempWorkspace(), "out")
            val result =
                KumlCli().test(
                    listOf("workspace", "render", demoRoot.absolutePath, "-o", outDir.absolutePath, "--mirror"),
                )
            result.statusCode shouldBe 0

            val mirroredDoc = File(outDir, "models/domain-classes.md")
            mirroredDoc.exists() shouldBe true
            val mirroredText = mirroredDoc.readText()
            mirroredText shouldContain "]("
            mirroredText shouldContain ".svg)"
            mirroredText shouldNotContain "```kuml"

            outDir.deleteRecursively()
        }

        test("workspace render --format png --width 600 writes PNG assets") {
            val outDir = File(tempWorkspace(), "out")
            val result =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "render",
                        demoRoot.absolutePath,
                        "-o",
                        outDir.absolutePath,
                        "-f",
                        "png",
                        "-w",
                        "600",
                    ),
                )
            result.statusCode shouldBe 0
            val classesPng = File(outDir, "models/domain-classes.png")
            classesPng.exists() shouldBe true
            (classesPng.length() > 0) shouldBe true

            outDir.deleteRecursively()
        }

        test("workspace render keeps going after a broken block and reports a failure") {
            val root = tempWorkspace()
            File(root, "good.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml
                |classDiagram(name = "Good") {
                |    val a = classOf(name = "A")
                |}
                |```
                """.trimMargin(),
            )
            File(root, "bad.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml
                |this is not valid kuml syntax @@@ ((
                |```
                """.trimMargin(),
            )

            val outDir = File(root, "out")
            val result =
                KumlCli().test(
                    listOf("workspace", "render", root.absolutePath, "-o", outDir.absolutePath, "--no-mirror"),
                )
            result.statusCode shouldBe 0
            result.output shouldContain "Rendered 1"
            result.output shouldContain "failed 1"
            File(outDir, "good.svg").exists() shouldBe true
            File(outDir, "bad.svg").exists() shouldBe false

            val strictResult =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "render",
                        root.absolutePath,
                        "-o",
                        outDir.absolutePath,
                        "--no-mirror",
                        "--strict",
                    ),
                )
            strictResult.statusCode shouldBe ExitCodes.SCRIPT_ERROR

            root.deleteRecursively()
        }

        test("workspace render sanitises a path-traversal block name and stays inside the output dir") {
            val root = tempWorkspace()
            File(root, "evil.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml {name="../../../../tmp/pwned"}
                |classDiagram(name = "Evil") {
                |    val a = classOf(name = "A")
                |}
                |```
                """.trimMargin(),
            )

            val outDir = File(root, "out")
            val result =
                KumlCli().test(
                    listOf("workspace", "render", root.absolutePath, "-o", outDir.absolutePath, "--no-mirror"),
                )
            result.statusCode shouldBe 0
            result.output shouldContain "Rendered 1"

            // No file escaped the output directory.
            File("/tmp/pwned.svg").exists() shouldBe false
            File(root.parentFile, "pwned.svg").exists() shouldBe false

            // The sanitised stem (only the last path segment survives) landed safely inside outDir.
            val written = outDir.walkTopDown().filter { it.isFile }.toList()
            written.size shouldBe 1
            written.single().name shouldBe "pwned.svg"

            root.deleteRecursively()
        }
    })
