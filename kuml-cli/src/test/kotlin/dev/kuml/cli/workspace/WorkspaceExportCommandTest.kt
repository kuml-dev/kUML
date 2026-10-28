package dev.kuml.cli.workspace

import com.github.ajalt.clikt.testing.test
import dev.kuml.cli.ExitCodes
import dev.kuml.cli.KumlCli
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files

class WorkspaceExportCommandTest :
    FunSpec({
        val demoRoot = File("src/test/resources/okf-workspace")
        val examples = File("../kuml-examples/src/main/resources/knowledge-workspaces")

        fun copy(src: File): File {
            val dst = Files.createTempDirectory("kuml-export-cli").toFile()
            src.copyRecursively(dst, overwrite = true)
            return dst
        }

        fun export(
            root: File,
            vararg args: String,
        ) = KumlCli().test(listOf("workspace", "export", root.absolutePath, "--format", "pdf") + args.toList())

        fun text(f: File): String = Loader.loadPDF(f).use { PDFTextStripper().getText(it) }

        test("handbook of the demo workspace renders real diagrams and a facts table") {
            val root = copy(demoRoot)
            val out = File(root, "book.pdf")
            val r = export(root, "-o", out.absolutePath, "--lang", "en")
            r.statusCode shouldBe 0
            r.stdout shouldContain "Wrote 1 file(s)"
            Loader.loadPDF(out).use { it.numberOfPages shouldBeGreaterThan 2 }
            text(out) shouldContain "Elements"
            text(out) shouldContain "Source: models/"
        }

        test("both bundled example workspaces export in all three kinds") {
            for (name in listOf("sample-association-charter", "sample-support-ticket-process")) {
                val root = copy(File(examples, name))
                val book = File(root, "$name.pdf")
                export(root, "-o", book.absolutePath, "--draft", "--page", "a3").statusCode shouldBe 0
                Loader.loadPDF(book).use { it.numberOfPages shouldBeGreaterThan 3 }
                val dir = File(root, "perdoc")
                export(root, "--kind", "per-document", "-o", dir.absolutePath).statusCode shouldBe 0
                (dir.listFiles()!!.size) shouldBeGreaterThan 3
                val folio = File(root, "folio.pdf")
                export(root, "--kind", "diagrams", "-o", folio.absolutePath).statusCode shouldBe 0
                Loader.loadPDF(folio).use { it.numberOfPages shouldBe 3 }
            }
        }

        test("default output lands in .kuml-out/export and is excluded from later scans") {
            val root = copy(demoRoot)
            export(root).statusCode shouldBe 0
            File(root, ".kuml-out/export").listFiles { f -> f.name.endsWith(".pdf") }!!.size shouldBe 1
        }

        test("existing output needs --force: exit 2, then 0") {
            val root = copy(demoRoot)
            val out = File(root, "x.pdf")
            export(root, "-o", out.absolutePath).statusCode shouldBe 0
            val again = export(root, "-o", out.absolutePath)
            again.statusCode shouldBe ExitCodes.USAGE
            again.stderr shouldContain "--force"
            export(root, "-o", out.absolutePath, "--force").statusCode shouldBe 0
        }

        test("usage errors: scope escape, bad date, ai-notes, split without folio") {
            val root = copy(demoRoot)
            export(root, "--scope", "folder:../etc").statusCode shouldBe ExitCodes.USAGE
            export(root, "--scope", "../x.md").statusCode shouldBe ExitCodes.USAGE
            export(root, "--date", "yesterday").statusCode shouldBe ExitCodes.USAGE
            export(root, "--ai-notes").statusCode shouldBe ExitCodes.USAGE
            export(root, "--split").statusCode shouldBe ExitCodes.USAGE
        }

        test("a failing diagram: warning exit 0, --strict exit 3 without writing") {
            val root = copy(demoRoot)
            File(root, "models/broken.md").writeText(
                "---\ntype: UmlClassDiagram\n---\n# Broken\n\n```kuml\nthis is not valid kotlin (\n```\n",
            )
            val out = File(root, "b.pdf")
            val lenient = export(root, "-o", out.absolutePath, "--lang", "en")
            lenient.statusCode shouldBe 0
            lenient.stderr shouldContain "OKF-E-007"
            text(out).replace("\n", " ") shouldContain "Diagram could not be rendered"
            val out2 = File(root, "c.pdf")
            val strict = export(root, "-o", out2.absolutePath, "--strict")
            strict.statusCode shouldBe ExitCodes.SCRIPT_ERROR
            out2.exists() shouldBe false
        }

        test("diagram facts extraction: UML elements and the RenderPipeline SVG match run output") {
            val root = copy(demoRoot)
            val script =
                File(root, "models/domain-classes.md")
                    .readText()
                    .substringAfter("```kuml")
                    .substringBefore("```")
                    .trim()
            val tmp = File.createTempFile("facts", ".kuml.kts").also { it.writeText(script) }
            val r =
                dev.kuml.cli.RenderPipeline
                    .renderToSvg(input = tmp, themeName = null)
            val facts = DiagramFactsExtractor.from(r.extracted)!!
            (facts.elements.isNotEmpty()) shouldBe true
            r.svg shouldContain "<svg"
            val out = Files.createTempFile("run", ".svg")
            dev.kuml.cli.RenderPipeline
                .run(input = tmp, output = out, format = "svg", width = 0, themeName = null)
            Files.readString(out) shouldBe r.svg
        }
    })
