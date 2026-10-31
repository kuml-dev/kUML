package dev.kuml.workspace.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import java.io.File

class HandbookPdfTest :
    FunSpec({
        val root = TestSupport.tempDir()
        val ws = TestSupport.workspace(dir = root)
        val out = File(TestSupport.tempDir(), "handbook.pdf")
        val facts =
            DiagramFacts(
                elements = listOf(ElementRow(kind = "Class", name = "Vorstand", detail = "3 attributes")),
                traces = emptyList(),
                notes = listOf(ModelNote(text = "TODO: Quorum klären", anchors = emptyList(), open = true)),
            )
        val result = TestSupport.export(ws = ws, options = TestSupport.options(), source = TestSupport.source(facts = facts), target = out)

        test("handbook has pages, one file, no failures") {
            result.files.size shouldBe 1
            result.pageCount shouldBeGreaterThan 3
            result.failures.size shouldBe 0
        }

        test("bookmarks follow chapter order from index.md and generated chapters come last") {
            TestSupport.open(out).use { pdf ->
                val marks = TestSupport.bookmarks(pdf)
                marks shouldContainInOrder
                    listOf("Erstes Kapitel", "Modelle", "Organe", "Offene Punkte", "Glossar", "Weitere Dokumente", "Anhangsdokument")
            }
        }

        test("cover, table of contents and header/footer text") {
            TestSupport.open(out).use { pdf ->
                TestSupport.text(pdf = pdf, page = 1) shouldContain "Muster Handbuch"
                TestSupport.text(pdf = pdf, page = 1) shouldContain "Erika Mustermann"
                TestSupport.text(pdf = pdf, page = 1) shouldContain "Offene Punkte:"
                TestSupport.text(pdf = pdf, page = 2) shouldContain "Inhaltsverzeichnis"
                TestSupport.text(pdf = pdf, page = 2) shouldContain "Erstes Kapitel"
                TestSupport.text(pdf = pdf, page = 3) shouldContain "Seite 3 von ${pdf.numberOfPages}"
                TestSupport.text(pdf = pdf, page = 3) shouldContain "Version 1.2"
                TestSupport.text(pdf = pdf, page = 1) shouldNotContain "Seite 1 von"
            }
        }

        test("umlauts survive and javascript links stay plain text") {
            TestSupport.open(out).use { pdf ->
                val all = TestSupport.allText(pdf)
                all shouldContain "ÄÖÜäöüß"
                all shouldContain "javascript"
                val uris =
                    pdf.pages
                        .flatMap { it.annotations }
                        .filterIsInstance<PDAnnotationLink>()
                        .mapNotNull { (it.action as? PDActionURI)?.uri }
                uris.contains("https://example.com/x") shouldBe true
                uris.none { it.startsWith("javascript") } shouldBe true
            }
        }

        test("internal links exist and every font is embedded") {
            TestSupport.open(out).use { pdf ->
                val internal =
                    pdf.pages
                        .flatMap { it.annotations }
                        .filterIsInstance<PDAnnotationLink>()
                        .count { it.action is PDActionGoTo }
                internal shouldBeGreaterThan 3
                pdf.pages.forEach { p ->
                    p.resources.fontNames.forEach { n ->
                        val font = p.resources.getFont(n)
                        font.isEmbedded shouldBe true
                        (font is PDType0Font) shouldBe true
                    }
                }
            }
        }

        test("metadata: info dictionary, XMP and producer") {
            TestSupport.open(out).use { pdf ->
                pdf.documentInformation.title shouldBe "Muster Handbuch"
                pdf.documentInformation.author shouldBe "Erika Mustermann"
                pdf.documentInformation.producer shouldBe "kUML test"
                pdf.documentInformation.creator shouldBe "kUML"
                pdf.documentInformation.creationDate shouldNotBe null
                val xmp =
                    pdf.documentCatalog.metadata
                        .exportXMPMetadata()
                        .readBytes()
                        .toString(Charsets.UTF_8)
                xmp shouldContain "Muster Handbuch"
                xmp shouldContain "kUML test"
            }
        }

        test("open items are collected from tasks, markers, callouts, status and model notes") {
            TestSupport.open(out).use { pdf ->
                val all = TestSupport.allText(pdf).replace("\n", " ")
                all shouldContain "Aufgabe offen"
                all shouldContain "TODO: Rechtschreibung"
                all shouldContain "Quorum klären"
                all shouldContain "Status: offen"
            }
        }

        test("traceability chapter only appears with requirement facts") {
            TestSupport.open(out).use { pdf -> TestSupport.bookmarks(pdf).contains("Rückverfolgbarkeit") shouldBe false }
            val withTrace =
                DiagramFacts(
                    elements = emptyList(),
                    traces =
                        listOf(
                            TraceLink(kind = TraceKind.SATISFY, sourceName = "Motor", targetName = "REQ-1", diagramName = "d"),
                            TraceLink(kind = TraceKind.VERIFY, sourceName = "Test A", targetName = "REQ-1", diagramName = "d"),
                        ),
                    notes = emptyList(),
                )
            val f = File(TestSupport.tempDir(), "trace.pdf")
            TestSupport.export(ws = ws, options = TestSupport.options(), source = TestSupport.source(facts = withTrace), target = f)
            TestSupport.open(f).use { pdf ->
                TestSupport.bookmarks(pdf).contains("Rückverfolgbarkeit") shouldBe true
                val all = TestSupport.allText(pdf).replace("\n", " ")
                all shouldContain "REQ-1"
                all shouldContain "Motor"
                all shouldContain "Test A"
            }
        }

        test("page formats and draft watermark") {
            val f = File(TestSupport.tempDir(), "a3.pdf")
            TestSupport.export(
                ws = ws,
                options =
                    TestSupport.options {
                        copy(page = PageFormat.A3, draft = true, language = PdfLanguage.EN)
                    },
                target = f,
            )
            TestSupport.open(f).use { pdf ->
                Math.round(pdf.getPage(0).mediaBox.width) shouldBe 842
                Math.round(pdf.getPage(0).mediaBox.height) shouldBe 1191
                (0 until pdf.numberOfPages).forEach { i -> TestSupport.text(pdf = pdf, page = i + 1) shouldContain "DRAFT" }
                TestSupport.text(pdf = pdf, page = 3) shouldContain "Page 3 of"
            }
            TestSupport.open(out).use { pdf -> TestSupport.allText(pdf) shouldNotContain "ENTWURF" }
        }
    })

private infix fun Any?.shouldNotBe(other: Any?) {
    (this != other) shouldBe true
}
