package dev.kuml.workspace.pdf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripperByArea
import java.awt.geom.Rectangle2D
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TilingIntegrationTest :
    FunSpec({
        val clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC)
        val longTitle = "Sehr langer Diagrammtitel ".repeat(9).trim()

        fun docMd(
            title: String,
            type: String = "Article",
        ) = "---\ntype: $type\ntitle: $title\n---\n# $title\n\n```kuml\n// fake\n```\n"

        fun workspace(
            index: String? = null,
            withLong: Boolean = true,
        ): File {
            val dir = TestSupport.tempDir()
            val extra =
                mutableMapOf(
                    "gross/a3.md" to docMd("Auf A3"),
                    "gross/kachel.md" to docMd("Kachel Diagramm"),
                    "klein/eins.md" to docMd("Klein Eins"),
                    "klein/zwei.md" to docMd("Klein Zwei"),
                    "klein/drei.md" to docMd("Klein Drei"),
                )
            if (withLong) extra["gross/lang.md"] = docMd(longTitle)
            if (index != null) extra["index.md"] = index
            TestSupport.workspace(dir = dir, extra = extra)
            return dir
        }

        fun pick(
            path: String,
            @Suppress("UNUSED_PARAMETER") idx: Int,
        ) = when (path) {
            "gross/a3.md" -> TestSupport.svg(w = 1800, h = 1200)
            "gross/kachel.md" -> TestSupport.svg(w = 4000, h = 2500)
            "gross/lang.md" -> TestSupport.svg(w = 4000, h = 2500)
            else -> TestSupport.svg(w = 400, h = 240)
        }

        fun export(
            dir: File,
            lang: PdfLanguage,
            tiling: Boolean = true,
        ): Triple<File, PdfExportResult, PreparedExport?> {
            val ws =
                dev.kuml.workspace.WorkspaceScanner
                    .scan(root = dir)
            val prepared =
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options = TestSupport.options { copy(language = lang, tiling = tiling) },
                    diagrams = TestSupport.sourceBy(::pick),
                    clock = clock,
                )
            val out = File(TestSupport.tempDir(), "h.pdf")
            val res = WorkspacePdfExporter.write(prepared = prepared, target = out, overwrite = true)
            return Triple(out, res, null)
        }

        fun topLine(
            pdf: PDDocument,
            page: Int,
        ): String {
            val p = pdf.getPage(page)
            val stripper = PDFTextStripperByArea()
            // header strip below the running header: margin .. margin + titleH
            val h = p.mediaBox.height.toDouble()
            val w = p.mediaBox.width.toDouble()
            stripper.addRegion("t", Rectangle2D.Double(0.0, 56.0, w, 24.0))
            stripper.extractRegions(p)
            check(h > 0)
            return stripper.getTextForRegion("t").trim().replace('\n', ' ')
        }

        for (lang in PdfLanguage.entries) {
            val de = lang == PdfLanguage.DE
            context("handbook with tiles in $lang") {
                val dir = workspace(index = null)
                val (file, res, _) = export(dir, lang)

                test("part pages, A3 page and summary") {
                    TestSupport.open(file).use { pdf ->
                        val all = TestSupport.allText(pdf)
                        for (i in 1..9) {
                            all shouldContain (if (de) "Teil $i von 9" else "Part $i of 9")
                        }
                        all shouldNotContain (if (de) "von 10" else "of 10")
                        val a3 =
                            (0 until pdf.numberOfPages).filter {
                                pdf.getPage(it).mediaBox.height > 1000f ||
                                    pdf.getPage(it).mediaBox.width > 1000f
                            }
                        // exactly one A3 page; its neighbours are A4 again
                        a3.size shouldBe 1
                        val box = pdf.getPage(a3.single()).mediaBox
                        box.width shouldBe (1190.55f plusOrMinus 0.1f)
                        box.height shouldBe (841.89f plusOrMinus 0.1f)
                        pdf.getPage(a3.single() + 1).mediaBox.let { it.width shouldBe (595.28f plusOrMinus 0.1f) }
                    }
                    res.warnings.shouldBeEmpty()
                    res.layoutSummary.a3Diagrams shouldBe 1
                    res.layoutSummary.tiledDiagrams shouldBe 2
                    res.layoutSummary.tilePages shouldBe 18
                    res.layoutSummary.illegibleDiagrams shouldBe 0
                }

                test("page count is cover + toc + text + planned tiles") {
                    TestSupport.open(file).use { pdf ->
                        val tilePages =
                            (0 until pdf.numberOfPages).count {
                                TestSupport
                                    .text(
                                        pdf = pdf,
                                        page = it + 1,
                                    ).contains(if (de) "Teil " else "Part ")
                            }
                        tilePages shouldBe 18
                    }
                }

                test("info dictionary and cover") {
                    TestSupport.open(file).use { pdf ->
                        val info = pdf.documentInformation
                        info.producer shouldBe "kUML test"
                        info.creator shouldBe "kUML"
                        info.subject shouldBe (if (de) "Handbuch" else "Handbook")
                        info.title shouldBe "Muster Handbuch"
                        info.creationDate.toInstant().toString() shouldBe "2026-10-31T10:00:00Z"
                        val cover = TestSupport.text(pdf = pdf, page = 1)
                        cover shouldContain (if (de) "Exportiert am" else "Exported on")
                        cover shouldContain (if (de) "31. Oktober 2026" else "31 October 2026")
                        cover shouldContain (if (de) "Autor" else "Author")
                        cover shouldContain "Erika Mustermann"
                        cover shouldContain (if (de) "Dokumente" else "Documents")
                        cover shouldContain (if (de) "Erstellt mit kUML test" else "Created with kUML test")
                        cover shouldNotContain "Organi"
                        cover shouldNotContain "–"
                    }
                }

                test("long title is shortened but keeps the tile suffix; bookmarks list the figure once, not per tile") {
                    TestSupport.open(file).use { pdf ->
                        val idx =
                            (0 until pdf.numberOfPages).first {
                                TestSupport.text(pdf = pdf, page = it + 1).contains(if (de) "Teil 1 von 9" else "Part 1 of 9") &&
                                    topLine(pdf, it).contains("Sehr langer")
                            }
                        val line = topLine(pdf, idx)
                        line shouldContain "…"
                        line shouldContain (if (de) "Teil 1 von 9 · Zeile 1, Spalte 1" else "Part 1 of 9 · row 1, column 1")
                        // section heading plus one figure bookmark on the first tile (bookmark titles are capped at 200 characters)
                        TestSupport.bookmarks(pdf).count { it == longTitle.take(200) } shouldBe 2
                    }
                }
            }
        }

        test("cover shows organization and version only when present") {
            val idx =
                "---\ntype: KumlWorkspace\ntitle: Mit Organisation\nauthor: A. Autor\norganization: Muster GmbH\nversion: 3.0\ndate: 2026-10-01\n---\n# Mit Organisation\n\n- [Eins](klein/eins.md)\n"
            val dir = workspace(index = idx, withLong = false)
            val (file, _, _) = export(dir, PdfLanguage.DE)
            TestSupport.open(file).use { pdf ->
                val cover = TestSupport.text(pdf = pdf, page = 1)
                cover shouldContain "Organisation"
                cover shouldContain "Muster GmbH"
                cover shouldContain "Version"
                cover shouldContain "1. Oktober 2026"
            }
            val bare = "---\ntype: KumlWorkspace\ntitle: Ohne Angaben\n---\n# Ohne Angaben\n\n- [Eins](klein/eins.md)\n"
            val (file2, _, _) = export(workspace(index = bare, withLong = false), PdfLanguage.EN)
            TestSupport.open(file2).use { pdf ->
                val cover = TestSupport.text(pdf = pdf, page = 1)
                cover shouldNotContain "Organization"
                cover shouldNotContain "Version"
                cover shouldNotContain "Author"
                cover shouldContain "Documents"
            }
        }

        test("without tiling the big diagrams are illegible warnings with localized text") {
            val (_, res, _) = export(workspace(index = null, withLong = false), PdfLanguage.EN, tiling = false)
            res.warnings.size shouldBe 1
            val w = res.warnings.single()
            w.code shouldBe ScaleWarningCode.ILLEGIBLE
            w.message shouldBe "illegible (scale ${String.format(java.util.Locale.ROOT, "%.2f", w.scale)} < 0.50)"
            w.message shouldEndWith "< 0.50)"
            w.suggestion shouldBe "enable tiling"
            res.layoutSummary.illegibleDiagrams shouldBe 1
            res.layoutSummary.tiledDiagrams shouldBe 0
            listOf(w.title) shouldContain "Kachel Diagramm"
        }
    })
