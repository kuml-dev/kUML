package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.KumlCli
import dev.kuml.layout.LayoutEngineRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

class WorkspaceExportLatexKnowledgeCommandTest :
    FunSpec({
        beforeEach { LayoutEngineRegistry.clear() }
        afterEach { LayoutEngineRegistry.clear() }

        fun workspace(configure: (File) -> Unit = {}): File {
            val root = Files.createTempDirectory("kuml-latex-knowledge").toFile()
            File("src/test/resources/latex-knowledge").copyRecursively(root)
            configure(root)
            return root
        }

        fun run(
            root: File,
            vararg args: String,
        ) = KumlCli().test(listOf("workspace", "export", root.absolutePath, "--format", "latex") + args.toList())

        test("a Knowledge workspace is exported with main.tex, preamble, figures and images").config(timeout = 180.seconds) {
            val root = workspace()
            try {
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath)
                r.statusCode shouldBe 0
                r.stdout shouldContain "Wrote main.tex, kuml-preamble.tex, 4 figure(s), 1 image(s) for 5 document(s)"
                r.stdout shouldContain "0 failed, 0 skipped, 0 excluded from main.tex, 1 warnings"
                r.stderr shouldNotContain "zusammenarbeit"
                r.stderr shouldContain "WARN ueberblick.md"
                r.stderr shouldContain "NOTE ueberblick.md"
                File(out, "main.tex").isFile shouldBe true
                File(out, "kuml-preamble.tex").isFile shouldBe true
                out.resolve("figures").list()!!.size shouldBe 4
                out
                    .resolve("images")
                    .list()!!
                    .single()
                    .endsWith(".png") shouldBe true
                val main = File(out, "main.tex").readText()
                main shouldContain "\\usepackage[shorthands=off,ngerman]{babel}"
                main shouldContain "\\chapter{Überblick}"
                main shouldContain "\\date{2026-11-01}"
            } finally {
                root.deleteRecursively()
            }
        }

        test("--strict exits 3 on a warning and writes nothing").config(timeout = 180.seconds) {
            val root = workspace()
            try {
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath, "--strict")
                r.statusCode shouldBe 3
                r.stderr shouldContain "nothing written"
                out.exists() shouldBe false
            } finally {
                root.deleteRecursively()
            }
        }

        test("a failing kuml block exits 3 but the other files are written").config(timeout = 180.seconds) {
            val root =
                workspace {
                    File(
                        it,
                        "modelle/kaputt.md",
                    ).writeText("---\ntype: Article\ntitle: Kaputt\n---\n# Kaputt\n\n```kuml\nthis is not kotlin (\n```\n")
                    File(it, "index.md").appendText("- [Kaputt](modelle/kaputt.md)\n")
                }
            try {
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath)
                r.statusCode shouldBe 3
                r.stderr shouldContain "ERROR modelle/kaputt.md"
                File(out, "main.tex").isFile shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }

        test("a Knowledge workspace without diagrams is valid and exits 0").config(timeout = 180.seconds) {
            val root = Files.createTempDirectory("kuml-latex-knowledge-plain").toFile()
            try {
                File(root, "index.md").writeText("---\ntype: KumlWorkspace\ntitle: Nur Text\n---\n# Nur Text\n\n- [A](a.md)\n")
                File(root, "a.md").writeText("---\ntype: Article\ntitle: A\n---\n# A\n\nText.\n")
                val out = File(root, "out")
                val r = run(root, "-o", out.absolutePath)
                r.statusCode shouldBe 0
                File(out, "main.tex").isFile shouldBe true
                r.stdout shouldContain "0 figure(s)"
            } finally {
                root.deleteRecursively()
            }
        }

        test("existing output without --force exits 2 with a hint").config(timeout = 180.seconds) {
            val root = workspace()
            try {
                val out = File(root, "out")
                run(root, "-o", out.absolutePath).statusCode shouldBe 0
                val again = run(root, "-o", out.absolutePath)
                again.statusCode shouldBe 2
                again.stderr shouldContain "--force"
                run(root, "-o", out.absolutePath, "--force").statusCode shouldBe 0
            } finally {
                root.deleteRecursively()
            }
        }

        test("PDF-only options are still rejected").config(timeout = 60.seconds) {
            val root = workspace()
            try {
                val r = run(root, "--kind", "diagrams")
                r.statusCode shouldBe 2
                r.stderr shouldContain "--kind"
            } finally {
                root.deleteRecursively()
            }
        }

        test("an unknown theme turns every diagram into an error").config(timeout = 180.seconds) {
            val root = workspace()
            try {
                val r = run(root, "-o", File(root, "out").absolutePath, "--theme", "does-not-exist")
                r.statusCode shouldBe 3
                r.stderr shouldContain "ERROR"
            } finally {
                root.deleteRecursively()
            }
        }

        test("the default output lands in .kuml-out/export/latex and is not scanned again").config(timeout = 180.seconds) {
            val root = workspace()
            try {
                run(root).statusCode shouldBe 0
                File(root, ".kuml-out/export/latex/main.tex").isFile shouldBe true
                val again = run(root, "--force")
                again.statusCode shouldBe 0
                again.stdout shouldContain "for 5 document(s)"
            } finally {
                root.deleteRecursively()
            }
        }

        test("an Engineering folder is unaffected: a script next to Markdown keeps the folder export").config(timeout = 180.seconds) {
            val root = workspace { File("src/test/resources/latex/uml-class.kuml.kts").copyTo(File(it, "a.kuml.kts")) }
            try {
                val r = run(root, "-o", File(root, "out").absolutePath)
                r.statusCode shouldBe 0
                r.stdout shouldContain "Wrote 1 figure(s) plus kuml-preamble.tex and main.tex"
                r.stdout shouldNotContain "document(s)"
            } finally {
                root.deleteRecursively()
            }
        }
    })
