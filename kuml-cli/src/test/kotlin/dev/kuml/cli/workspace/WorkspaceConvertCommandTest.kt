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

/**
 * `kuml workspace convert` (FT-7) — CLI-level tests covering both directions, the
 * documented directionality of losslessness, and the FT-7 finding codes
 * (`OKF-C-001`..`OKF-C-004`).
 */
class WorkspaceConvertCommandTest :
    FunSpec({

        val demoRoot = File("src/test/resources/okf-workspace")

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-convert").toFile()

        test("--to okf wraps a .kuml.kts script with the correct type/title and passes workspace validate cleanly") {
            val root = tempWorkspace()
            val script = File(root, "hello.kuml.kts")
            script.writeText(
                """
                classDiagram(name = "Hello World") {
                    val a = classOf(name = "A")
                }
                """.trimIndent() + "\n",
            )

            val outDir = File(root, "out")
            val result =
                KumlCli().test(listOf("workspace", "convert", script.absolutePath, "--to", "okf", "--output", outDir.absolutePath))
            result.statusCode shouldBe 0

            val note = File(outDir, "hello.md")
            note.exists() shouldBe true
            val text = note.readText()
            text shouldContain "type: UmlClassDiagram"
            text shouldContain "title: Hello World"
            text shouldContain "```kuml"

            // exit 0 means zero ERROR-severity findings; the single note isn't a
            // 'type: KumlWorkspace' index, so OKF-W-006 (WARNING, missing index.md) still
            // fires here and is expected -- convert's job is per-note conformance, not
            // synthesizing a full workspace index (see --scaffold-index note in the plan).
            val validateResult = KumlCli().test(listOf("workspace", "validate", outDir.absolutePath))
            validateResult.statusCode shouldBe 0
            validateResult.output shouldNotContain "ERROR"

            root.deleteRecursively()
        }

        test("--to kts extracts the ```kuml block source verbatim from the demo workspace fixture") {
            val outDir = File(tempWorkspace(), "out")
            val result =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "convert",
                        demoRoot.absolutePath,
                        "--to",
                        "kts",
                        "--output",
                        outDir.absolutePath,
                    ),
                )
            result.statusCode shouldBe 0

            val extracted = File(outDir, "models/domain-classes.kuml.kts")
            extracted.exists() shouldBe true
            val originalBlock =
                File(demoRoot, "models/domain-classes.md")
                    .readText()
                    .substringAfter("```kuml\n")
                    .substringBefore("\n```")
            extracted.readText().trimEnd('\n') shouldBe originalBlock.trimEnd('\n')

            outDir.deleteRecursively()
        }

        test("kts -> okf -> kts round trip recovers the original DSL source byte-for-byte (modulo trailing newline)") {
            val root = tempWorkspace()
            val original =
                """
                classDiagram(name = "Round Trip") {
                    val a = classOf(name = "Author") {
                        attribute(name = "id", type = "UUID")
                    }
                    val b = classOf(name = "Document") {
                        attribute(name = "title", type = "String")
                    }
                    association(source = a, target = b) {
                        source { multiplicity(spec = "1") }
                        target { multiplicity(spec = "0..*"); role = "documents" }
                    }
                }
                """.trimIndent() + "\n"
            val script = File(root, "roundtrip.kuml.kts")
            script.writeText(original)

            val okfDir = File(root, "okf")
            val toOkf = KumlCli().test(listOf("workspace", "convert", script.absolutePath, "--to", "okf", "--output", okfDir.absolutePath))
            toOkf.statusCode shouldBe 0

            val ktsDir = File(root, "kts")
            val toKts =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "convert",
                        File(okfDir, "roundtrip.md").absolutePath,
                        "--to",
                        "kts",
                        "--output",
                        ktsDir.absolutePath,
                    ),
                )
            toKts.statusCode shouldBe 0

            val roundTripped = File(ktsDir, "roundtrip.kuml.kts").readText()
            roundTripped.trimEnd('\n') shouldBe original.trimEnd('\n')

            root.deleteRecursively()
        }

        test("okf -> kts -> okf is lossy: prose docs produce no output and the regenerated note drops the original frontmatter") {
            val ktsDir = File(tempWorkspace(), "kts")
            val toKts =
                KumlCli().test(
                    listOf("workspace", "convert", demoRoot.absolutePath, "--to", "kts", "--output", ktsDir.absolutePath),
                )
            toKts.statusCode shouldBe 0
            // Prose/collection documents (Article, Concept, KumlWorkspace index) have no ```kuml
            // block at all -> nothing extracted for them.
            File(ktsDir, "articles/01-ueberblick.kuml.kts").exists() shouldBe false
            File(ktsDir, "concepts/Bestellung.kuml.kts").exists() shouldBe false
            File(ktsDir, "index.kuml.kts").exists() shouldBe false

            val okfDir = File(ktsDir.parentFile, "okf-again")
            val toOkf = KumlCli().test(listOf("workspace", "convert", ktsDir.absolutePath, "--to", "okf", "--output", okfDir.absolutePath))
            toOkf.statusCode shouldBe 0

            val regenerated = File(okfDir, "models/domain-classes.md").readText()
            // The diagram's own name becomes the new title -- the original frontmatter
            // title/tags ("Domain Classes" / [shop, uml, class-diagram]) do not survive.
            regenerated shouldContain "title: Shop Domain Classes"
            regenerated shouldNotContain "tags:"
            // The original note's own title ("Domain Classes", without "Shop") does not survive.
            regenerated shouldNotContain "title: Domain Classes\n"

            ktsDir.parentFile.deleteRecursively()
        }

        test("a document with two ```kuml blocks is split into <stem>-1/<stem>-2 and reported as OKF-C-002") {
            val root = tempWorkspace()
            File(root, "multi.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml
                |classDiagram(name = "First") {
                |    val a = classOf(name = "A")
                |}
                |```
                |
                |```kuml
                |classDiagram(name = "Second") {
                |    val b = classOf(name = "B")
                |}
                |```
                """.trimMargin(),
            )

            val outDir = File(root, "out")
            val result = KumlCli().test(listOf("workspace", "convert", root.absolutePath, "--to", "kts", "--output", outDir.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "OKF-C-002"
            File(outDir, "multi-1.kuml.kts").exists() shouldBe true
            File(outDir, "multi-2.kuml.kts").exists() shouldBe true

            val strictResult =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "convert",
                        root.absolutePath,
                        "--to",
                        "kts",
                        "--output",
                        outDir.absolutePath,
                        "--strict",
                        "--force",
                    ),
                )
            strictResult.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS

            root.deleteRecursively()
        }

        test("a diagram kind with no OKF vocabulary entry (packageDiagram) is reported as OKF-C-003 with a custom type") {
            val root = tempWorkspace()
            val script = File(root, "pkg.kuml.kts")
            script.writeText(
                """
                packageDiagram(name = "Packages") {
                }
                """.trimIndent() + "\n",
            )

            val outDir = File(root, "out")
            val result = KumlCli().test(listOf("workspace", "convert", script.absolutePath, "--to", "okf", "--output", outDir.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "OKF-C-003"
            File(outDir, "pkg.md").readText() shouldContain "type: UmlPackageDiagram"

            val strictResult =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "convert",
                        script.absolutePath,
                        "--to",
                        "okf",
                        "--output",
                        outDir.absolutePath,
                        "--strict",
                        "--force",
                    ),
                )
            strictResult.statusCode shouldBe ExitCodes.VALIDATION_VIOLATIONS

            root.deleteRecursively()
        }

        test("a script that fails to evaluate is reported as OKF-C-004 and does not abort the batch") {
            val root = tempWorkspace()
            File(root, "good.kuml.kts").writeText(
                """
                classDiagram(name = "Good") {
                    val a = classOf(name = "A")
                }
                """.trimIndent() + "\n",
            )
            File(root, "bad.kuml.kts").writeText("this is not valid kotlin @@@ ((\n")

            val outDir = File(root, "out")
            val result = KumlCli().test(listOf("workspace", "convert", root.absolutePath, "--to", "okf", "--output", outDir.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "OKF-C-004"
            File(outDir, "good.md").exists() shouldBe true
            File(outDir, "bad.md").exists() shouldBe false

            root.deleteRecursively()
        }

        test("a path-traversal ```kuml {name=\"...\"} block stays inside the output directory") {
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
            val result = KumlCli().test(listOf("workspace", "convert", root.absolutePath, "--to", "kts", "--output", outDir.absolutePath))
            result.statusCode shouldBe 0

            File("/tmp/pwned.kuml.kts").exists() shouldBe false
            File(root.parentFile, "pwned.kuml.kts").exists() shouldBe false
            val written = outDir.walkTopDown().filter { it.isFile }.toList()
            written.size shouldBe 1
            written.single().name shouldBe "pwned.kuml.kts"

            root.deleteRecursively()
        }

        test("convert aborts on an existing output file without --force, and succeeds with --force") {
            val root = tempWorkspace()
            val script = File(root, "hello.kuml.kts")
            script.writeText(
                """
                classDiagram(name = "Hello") {
                    val a = classOf(name = "A")
                }
                """.trimIndent() + "\n",
            )
            val outDir = File(root, "out")
            outDir.mkdirs()
            File(outDir, "hello.md").writeText("pre-existing content")

            val result = KumlCli().test(listOf("workspace", "convert", script.absolutePath, "--to", "okf", "--output", outDir.absolutePath))
            result.statusCode shouldBe 0
            result.output shouldContain "skipped 1"
            File(outDir, "hello.md").readText() shouldBe "pre-existing content"

            val forced =
                KumlCli().test(
                    listOf("workspace", "convert", script.absolutePath, "--to", "okf", "--output", outDir.absolutePath, "--force"),
                )
            forced.statusCode shouldBe 0
            File(outDir, "hello.md").readText() shouldContain "type: UmlClassDiagram"

            root.deleteRecursively()
        }

        test("-o json reports the converted/skipped/findings shape") {
            val root = tempWorkspace()
            val script = File(root, "hello.kuml.kts")
            script.writeText(
                """
                classDiagram(name = "Hello") {
                    val a = classOf(name = "A")
                }
                """.trimIndent() + "\n",
            )
            val outDir = File(root, "out")

            val result =
                KumlCli().test(
                    listOf(
                        "workspace",
                        "convert",
                        script.absolutePath,
                        "--to",
                        "okf",
                        "--output",
                        outDir.absolutePath,
                        "-o",
                        "json",
                    ),
                )
            result.statusCode shouldBe 0
            result.output shouldContain "\"converted\""
            result.output shouldContain "\"skipped\""
            result.output shouldContain "\"findings\""
            result.output shouldContain "hello.md"

            root.deleteRecursively()
        }
    })
