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
import java.nio.file.Path

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

        test("workspace validate reports an unknown type as WARN and exits 0 by default") {
            val root = tempWorkspace()
            File(root, "typo.md").writeText(
                """
                |---
                |type: UmlClassDigram
                |---
                |Body.
                """.trimMargin(),
            )

            val result = KumlCli().test(listOf("workspace", "validate", root.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "WARN"
            result.output shouldContain "OKF-W-002"

            root.deleteRecursively()
        }

        test("workspace validate --strict-vocabulary escalates an unknown type to ERROR and exits 5") {
            val root = tempWorkspace()
            File(root, "typo.md").writeText(
                """
                |---
                |type: UmlClassDigram
                |---
                |Body.
                """.trimMargin(),
            )

            val result =
                KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--strict-vocabulary"))
            result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
            result.output shouldContain "ERROR"
            result.output shouldContain "OKF-W-002"

            root.deleteRecursively()
        }

        test("workspace validate suggests a close vocabulary match for a near-miss type") {
            val root = tempWorkspace()
            File(root, "typo.md").writeText(
                """
                |---
                |type: UmlClassDigram
                |---
                |Body.
                """.trimMargin(),
            )

            val result = KumlCli().test(listOf("workspace", "validate", root.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "Did you mean 'UmlClassDiagram'?"

            root.deleteRecursively()
        }
        // ── Wave D: root normalisation, --document, output dir, multi-diagram ─────────

        fun demoCopy(): File {
            val tmp = tempWorkspace()
            demoRoot.copyRecursively(tmp, overwrite = true)
            return tmp
        }

        val multiDiagramDoc =
            """
            |---
            |type: Concept
            |---
            |# Multi
            |```kuml
            |c4Model(name = "Multi") {
            |    val p = person(name = "P")
            |    val s = softwareSystem(name = "S")
            |    systemContextDiagram(name = "SECRET-DIAGRAM-NAME") { include(p, s) }
            |    systemContextDiagram(name = "Second") { include(p, s) }
            |}
            |```
            """.trimMargin()

        val singleDiagramDoc =
            """
            |---
            |type: Concept
            |---
            |```kuml
            |classDiagram(name = "One") {
            |    classOf(name = "A")
            |}
            |```
            """.trimMargin()

        test("workspace info and validate work with a root given as 'dir/.' and 'dir/models/..'") {
            val tmp = demoCopy()
            try {
                val info = KumlCli().test(listOf("workspace", "info", "${tmp.absolutePath}/.", "-o", "json"))
                info.statusCode shouldBe 0
                info.output shouldContain "\"documentCount\": 5"

                val dot = KumlCli().test(listOf("workspace", "validate", "${tmp.absolutePath}/."))
                dot.statusCode shouldBe 0
                dot.output shouldContain "valid"
                dot.output shouldNotContain "OKF-W-006"

                val dotdot = KumlCli().test(listOf("workspace", "validate", "${tmp.absolutePath}/models/.."))
                dotdot.statusCode shouldBe 0
                dotdot.output shouldNotContain "OKF-W-006"
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("workspace validate works with a truly relative root path") {
            val tmp = demoCopy()
            try {
                val rel =
                    runCatching { Path.of(System.getProperty("user.dir")).relativize(tmp.toPath()).toString() }.getOrNull()
                if (rel != null) {
                    val result = KumlCli().test(listOf("workspace", "validate", rel, "-o", "json"))
                    result.statusCode shouldBe 0
                    result.output shouldNotContain "OKF-W-006"
                }
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("workspace render accepts 'dir/.' as root") {
            val tmp = demoCopy()
            try {
                val result = KumlCli().test(listOf("workspace", "render", "${tmp.absolutePath}/."))
                result.statusCode shouldBe 0
                result.output shouldContain "Rendered 2"
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("workspace validate --document checks exactly one document, without OKF-W-006") {
            val root = tempWorkspace()
            try {
                File(root, "good.md").writeText("---\ntype: Concept\n---\nOk.\n")
                File(root, "broken.md").writeText("no frontmatter at all\n")
                val ok = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--document", "good.md"))
                ok.statusCode shouldBe 0
                ok.output shouldContain "good.md: valid"
                ok.output shouldNotContain "OKF-W-006"
                ok.output shouldNotContain "OKF-E-001"

                val json =
                    KumlCli().test(
                        listOf("workspace", "validate", root.absolutePath, "--document", "good.md", "-o", "json"),
                    )
                json.output shouldContain "\"scope\": \"document\""
                json.output shouldContain "good.md"

                val bad = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--document", "broken.md"))
                bad.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
                bad.output shouldContain "OKF-E-001"
            } finally {
                root.deleteRecursively()
            }
        }

        test("workspace render --document renders only the selected documents") {
            val tmp = demoCopy()
            try {
                val outDir = File(tmp, "out")
                val one =
                    KumlCli().test(
                        listOf(
                            "workspace",
                            "render",
                            tmp.absolutePath,
                            "-o",
                            outDir.absolutePath,
                            "--document",
                            "models/domain-classes.md",
                        ),
                    )
                one.statusCode shouldBe 0
                one.output shouldContain "Rendered 1"
                File(outDir, "models/domain-classes.svg").exists() shouldBe true
                File(outDir, "models/checkout-state.svg").exists() shouldBe false

                val both =
                    KumlCli().test(
                        listOf(
                            "workspace",
                            "render",
                            tmp.absolutePath,
                            "-o",
                            outDir.absolutePath,
                            "--document",
                            "models/domain-classes.md",
                            "--document",
                            "models/checkout-state.md",
                        ),
                    )
                both.output shouldContain "Rendered 2"
                File(outDir, "models/checkout-state.svg").exists() shouldBe true
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("--document with an unknown or escaping path exits 2 and leaks no file content") {
            val parent = tempWorkspace()
            val root = File(parent, "ws").apply { mkdirs() }
            try {
                File(root, "models").mkdirs()
                File(root, "models/domain.md").writeText("---\ntype: Concept\n---\nx\n")
                File(parent, "secret.md").writeText("TOP-SECRET-MARKER")

                val unknown = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--document", "models/doman.md"))
                unknown.statusCode shouldBe ExitCodes.USAGE
                unknown.output shouldContain "Unknown document"
                unknown.output shouldContain "Did you mean 'models/domain.md'?"

                val traversal =
                    KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--document", "../secret.md"))
                traversal.statusCode shouldBe ExitCodes.USAGE
                traversal.output shouldContain "outside the workspace root"
                traversal.output shouldNotContain "TOP-SECRET-MARKER"

                val render =
                    KumlCli().test(listOf("workspace", "render", root.absolutePath, "--document", "../secret.md"))
                render.statusCode shouldBe ExitCodes.USAGE
                File(root, ".kuml-out").walkTopDown().count { it.isFile } shouldBe 0
            } finally {
                parent.deleteRecursively()
            }
        }

        test("rendering twice never re-scans its own output (hidden default and visible in-root dir)") {
            val tmp = demoCopy()
            try {
                fun docCount(): String {
                    val info = KumlCli().test(listOf("workspace", "info", tmp.absolutePath, "-o", "json"))
                    return Regex("\"documentCount\": (\\d+)").find(info.output)!!.groupValues[1]
                }
                val before = docCount()
                repeat(2) {
                    KumlCli().test(listOf("workspace", "render", tmp.absolutePath)).output shouldContain "Rendered 2"
                }
                docCount() shouldBe before
                repeat(2) {
                    KumlCli()
                        .test(listOf("workspace", "render", tmp.absolutePath, "-o", File(tmp, "rendered").absolutePath))
                        .output shouldContain "Rendered 2" // render excludes its own (visible) output dir
                }
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("mirroring into the workspace root or one of its ancestors is refused and leaves the sources untouched") {
            val tmp = demoCopy()
            try {
                val source = File(tmp, "models/domain-classes.md")
                val bytes = source.readBytes()
                val root = KumlCli().test(listOf("workspace", "render", tmp.absolutePath, "-o", tmp.absolutePath))
                root.statusCode shouldBe ExitCodes.USAGE
                val parent = KumlCli().test(listOf("workspace", "render", tmp.absolutePath, "-o", "${tmp.absolutePath}/.."))
                parent.statusCode shouldBe ExitCodes.USAGE
                source.readBytes().contentEquals(bytes) shouldBe true
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("render prints the output location and the in-root hint only for the default") {
            val tmp = demoCopy()
            try {
                val dflt = KumlCli().test(listOf("workspace", "render", tmp.absolutePath))
                dflt.output shouldContain "Output: "
                dflt.output shouldContain ".kuml-out"
                dflt.output shouldContain "excluded from scans"

                val explicit =
                    KumlCli().test(listOf("workspace", "render", tmp.absolutePath, "-o", File(tmp, "rendered").absolutePath))
                explicit.output shouldContain "Output: "
                explicit.output shouldNotContain "excluded from scans"
            } finally {
                tmp.deleteRecursively()
            }
        }

        test("render warns (OKF-W-008) when a block yields several diagrams; --strict turns it into exit 3") {
            val root = tempWorkspace()
            try {
                File(root, "multi.md").writeText(multiDiagramDoc)
                val outDir = File(root, "out")
                val warn =
                    KumlCli().test(
                        listOf("workspace", "render", root.absolutePath, "-o", outDir.absolutePath, "--no-mirror"),
                    )
                warn.statusCode shouldBe 0
                warn.output shouldContain "Rendered 1"
                warn.output shouldContain "warnings 1"
                warn.output shouldContain "OKF-W-008"
                warn.output shouldContain "multi.md:5"
                warn.output shouldContain "2 diagrams"
                // log hygiene: no diagram names from the block
                warn.output shouldNotContain "SECRET-DIAGRAM-NAME"
                File(outDir, "multi.svg").exists() shouldBe true

                val strict =
                    KumlCli().test(
                        listOf("workspace", "render", root.absolutePath, "-o", outDir.absolutePath, "--no-mirror", "--strict"),
                    )
                strict.statusCode shouldBe ExitCodes.SCRIPT_ERROR
                File(outDir, "multi.svg").exists() shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a block with a single diagram produces no OKF-W-008") {
            val root = tempWorkspace()
            try {
                File(root, "one.md").writeText(singleDiagramDoc)
                val result = KumlCli().test(listOf("workspace", "render", root.absolutePath, "--strict"))
                result.statusCode shouldBe 0
                result.output shouldContain "warnings 0"
                result.output shouldNotContain "OKF-W-008"
            } finally {
                root.deleteRecursively()
            }
        }

        test("validate --evaluate reports OKF-W-008; --strict makes it an error; plain validate only hints") {
            val root = tempWorkspace()
            try {
                File(root, "multi.md").writeText(multiDiagramDoc)

                val plain = KumlCli().test(listOf("workspace", "validate", root.absolutePath))
                plain.output shouldNotContain "OKF-W-008"
                plain.output shouldContain "only checked with --evaluate"

                val eval = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--evaluate"))
                eval.statusCode shouldBe 0
                eval.output shouldContain "WARN"
                eval.output shouldContain "OKF-W-008"
                eval.output shouldNotContain "SECRET-DIAGRAM-NAME"

                val strict =
                    KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--evaluate", "--strict"))
                strict.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
                strict.output shouldContain "ERROR"
                strict.output shouldContain "OKF-W-008"
            } finally {
                root.deleteRecursively()
            }
        }

        test("validate --evaluate reports a broken block as OKF-E-007; --document limits evaluation to one file") {
            val root = tempWorkspace()
            try {
                File(root, "ok.md").writeText(singleDiagramDoc)
                File(root, "bad.md").writeText(
                    "---\ntype: Concept\n---\n```kuml\nthis is not valid kuml syntax @@@ ((\n```\n",
                )

                val all = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--evaluate"))
                all.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
                all.output shouldContain "OKF-E-007"

                val one =
                    KumlCli().test(
                        listOf("workspace", "validate", root.absolutePath, "--evaluate", "--document", "ok.md"),
                    )
                one.statusCode shouldBe 0
                one.output shouldNotContain "OKF-E-007"
            } finally {
                root.deleteRecursively()
            }
        }
        test("validate --evaluate reports a block without a renderable diagram as OKF-E-007") {
            val root = tempWorkspace()
            try {
                File(root, "plain.md").writeText("---\ntype: Concept\n---\n```kuml\n42\n```\n")
                val result = KumlCli().test(listOf("workspace", "validate", root.absolutePath, "--evaluate"))
                result.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS
                result.output shouldContain "OKF-E-007"
                result.output shouldContain "no renderable diagram"
            } finally {
                root.deleteRecursively()
            }
        }

        test("render --document works with -o <root> and -o <ancestor> under --no-mirror") {
            val root = tempWorkspace()
            try {
                File(root, "a.md").writeText(singleDiagramDoc)
                for (out in listOf(root, root.parentFile)) {
                    val result =
                        KumlCli().test(
                            listOf("workspace", "render", root.absolutePath, "-o", out.absolutePath, "--no-mirror", "--document", "a.md"),
                        )
                    result.statusCode shouldBe 0
                    result.output shouldContain "Rendered 1"
                }
            } finally {
                File(root.parentFile, "a.svg").delete()
                root.deleteRecursively()
            }
        }

        test("mirroring into a root sub-directory that holds source documents is refused; a prior render output is reusable") {
            val root = tempWorkspace()
            try {
                File(root, "docs").mkdirs()
                File(root, "index.md").writeText(singleDiagramDoc)
                val sourceDoc = File(root, "docs/index.md")
                sourceDoc.writeText("---\ntype: Concept\n---\nORIGINAL\n")
                val refused = KumlCli().test(listOf("workspace", "render", root.absolutePath, "-o", File(root, "docs").absolutePath))
                refused.statusCode shouldBe ExitCodes.USAGE
                sourceDoc.readText() shouldContain "ORIGINAL"

                val out = File(root, "rendered")
                KumlCli().test(listOf("workspace", "render", root.absolutePath, "-o", out.absolutePath)).statusCode shouldBe 0
                KumlCli().test(listOf("workspace", "render", root.absolutePath, "-o", out.absolutePath)).statusCode shouldBe 0
            } finally {
                root.deleteRecursively()
            }
        }

        test("a legacy .kuml-out without marker is reused by a default render") {
            val root = tempWorkspace()
            try {
                File(root, "index.md").writeText(singleDiagramDoc)
                val legacy = File(root, ".kuml-out")
                legacy.mkdirs()
                File(legacy, "index.md").writeText("old output without marker\n")
                KumlCli().test(listOf("workspace", "render", root.absolutePath)).statusCode shouldBe 0
                File(root, "index.md").readText() shouldContain "```kuml"
            } finally {
                root.deleteRecursively()
            }
        }
    })
