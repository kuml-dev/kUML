package dev.kuml.workspace.latex

import dev.kuml.workspace.latex.LatexTestSupport.main
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

class LatexImageCollectorTest :
    FunSpec({
        fun setup(
            body: String,
            prepareDir: (File) -> Unit = { LatexTestSupport.png(file = File(it, "bilder/Bild ü\\x.png")) },
            options: LatexKnowledgeOptions = LatexTestSupport.options(),
        ): PreparedLatexKnowledge {
            val dir = LatexTestSupport.tempDir()
            prepareDir(dir)
            val ws =
                LatexTestSupport.workspace(
                    dir = dir,
                    files =
                        mapOf(
                            "index.md" to LatexTestSupport.index(links = listOf("a.md")),
                            "a.md" to LatexTestSupport.doc(title = "A", body = body),
                        ),
                )
            return LatexTestSupport.prepare(ws = ws, options = options)
        }

        test("an image is copied under a sanitised, extension-from-content name and included") {
            val p = setup("![Alt](bilder/ok.png)\n", prepareDir = { LatexTestSupport.png(file = File(it, "bilder/ok.png")) })
            p.imageCount shouldBe 1
            p.files.any { it.path == "images/i001-ok.png" && it is LatexOutputFile.Binary } shouldBe true
            p.main() shouldContain "\\includegraphics[width=0.10\\linewidth,height=0.8\\textheight,keepaspectratio]{images/i001-ok.png}"
            p.main() shouldContain "\\emph{Alt}"
        }

        test("the same file referenced twice is copied once") {
            val p =
                setup(
                    "![a](bilder/ok.png)\n\n![b](bilder/ok.png)\n",
                    prepareDir = { LatexTestSupport.png(file = File(it, "bilder/ok.png")) },
                )
            p.imageCount shouldBe 1
            Regex("includegraphics").findAll(p.main()).count() shouldBe 2
        }

        test("a name with umlauts and dots gets a plain ASCII stem") {
            val p =
                setup(
                    "![a](bilder/Grüße.v2.final.png)\n",
                    prepareDir = { LatexTestSupport.png(file = File(it, "bilder/Grüße.v2.final.png")) },
                )
            p.files.any { it.path == "images/i001-gru-e-v2-final.png" || it.path == "images/i001-grusse-v2-final.png" } shouldBe true
        }

        test("an image outside the workspace, remote or missing gives a warning and a visible placeholder") {
            val p =
                setup(
                    "![a](../geheim.png)\n\n![b](https://example.org/x.png)\n\n![c](bilder/fehlt.png)\n",
                    prepareDir = { LatexTestSupport.png(file = File(it.parentFile, "geheim.png")) },
                )
            p.imageCount shouldBe 0
            p.issues.count { it.level == LatexIssueLevel.WARN } shouldBe 3
            Regex("\\\\kumlmissing\\{").findAll(p.main().substringAfter("\\begin{document}")).count() shouldBe 3
            p.main() shouldNotContain "includegraphics"
        }

        test("a symbolic link to an image outside the root is refused") {
            val p =
                setup(
                    "![a](bilder/link.png)\n",
                    prepareDir = {
                        val outside = LatexTestSupport.tempDir()
                        LatexTestSupport.png(file = File(outside, "s.png"))
                        File(it, "bilder").mkdirs()
                        runCatching { Files.createSymbolicLink(File(it, "bilder/link.png").toPath(), File(outside, "s.png").toPath()) }
                    },
                )
            p.imageCount shouldBe 0
        }

        test("limits: image count, bytes and pixels") {
            val countLimited =
                setup(
                    "![a](bilder/a.png)\n\n![b](bilder/b.png)\n",
                    prepareDir = {
                        LatexTestSupport.png(file = File(it, "bilder/a.png"))
                        LatexTestSupport.png(file = File(it, "bilder/b.png"), w = 20)
                    },
                    options = LatexTestSupport.options { copy(limits = limits.copy(maxImages = 1)) },
                )
            countLimited.imageCount shouldBe 1
            val pixelLimited =
                setup(
                    "![a](bilder/a.png)\n",
                    prepareDir = { LatexTestSupport.png(file = File(it, "bilder/a.png"), w = 50, h = 50) },
                    options = LatexTestSupport.options { copy(limits = limits.copy(maxImagePixels = 40)) },
                )
            pixelLimited.imageCount shouldBe 0
            val byteLimited =
                setup(
                    "![a](bilder/a.png)\n",
                    prepareDir = { LatexTestSupport.png(file = File(it, "bilder/a.png")) },
                    options = LatexTestSupport.options { copy(limits = limits.copy(maxImageBytes = 10)) },
                )
            byteLimited.imageCount shouldBe 0
        }
    })
