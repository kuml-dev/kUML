package dev.kuml.workspace.pdf

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.time.Duration

class ExportBehaviorTest :
    FunSpec({
        fun fresh() = TestSupport.workspace(dir = TestSupport.tempDir())

        test("planner: reading order, glossary separated, appendix alphabetical, empty scope rejected") {
            val ws = fresh()
            val plan =
                dev.kuml.workspace.pdf.plan.ExportPlanner
                    .plan(ws = ws, scope = ExportScope.All)
            plan.chapters.map { it.doc.relativePath } shouldBe listOf("chapters/eins.md", "models/index.md", "models/organe.md")
            plan.chapters.map { it.depth } shouldBe listOf(0, 0, 1)
            plan.glossary.map { it.relativePath } shouldBe listOf("glossary/index.md")
            plan.appendix.map { it.relativePath } shouldBe listOf("loose/anhang.md")
            shouldThrow<PdfExportException> {
                dev.kuml.workspace.pdf.plan.ExportPlanner
                    .plan(ws = ws, scope = ExportScope.Folder("nonexistent"))
            }.code shouldBe
                PdfExportException.Code.EMPTY_SCOPE
        }

        test("planner: scope escapes are rejected") {
            val ws = fresh()
            shouldThrow<PdfExportException> {
                dev.kuml.workspace.pdf.plan.ExportPlanner
                    .plan(ws = ws, scope = ExportScope.Folder("../x"))
            }.code shouldBe
                PdfExportException.Code.PATH_OUTSIDE_ROOT
            shouldThrow<PdfExportException> {
                dev.kuml.workspace.pdf.plan.ExportPlanner
                    .plan(ws = ws, scope = ExportScope.Documents(listOf("../etc/passwd.md")))
            }.code shouldBe
                PdfExportException.Code.PATH_OUTSIDE_ROOT
            shouldThrow<PdfExportException> {
                dev.kuml.workspace.pdf.plan.ExportPlanner
                    .plan(ws = ws, scope = ExportScope.Documents(listOf("/etc/passwd.md")))
            }.code shouldBe
                PdfExportException.Code.PATH_OUTSIDE_ROOT
            val plan =
                dev.kuml.workspace.pdf.plan.ExportPlanner
                    .plan(ws = ws, scope = ExportScope.Folder("chapters"))
            plan.all.map { it.relativePath } shouldBe listOf("chapters/eins.md")
        }

        test("planner: symlinked scope folder is rejected") {
            val root = TestSupport.tempDir()
            val ws = TestSupport.workspace(dir = root)
            val outside = TestSupport.tempDir()
            File(outside, "x.md").writeText("# x")
            runCatching { Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath()) }.onSuccess {
                shouldThrow<PdfExportException> {
                    dev.kuml.workspace.pdf.plan.ExportPlanner
                        .plan(ws = ws, scope = ExportScope.Folder("link"))
                }.code shouldBe
                    PdfExportException.Code.PATH_OUTSIDE_ROOT
            }
        }

        test("diagram placer: wide diagram without tiling is illegible, with tiling it is split without warning") {
            val ws = fresh()
            val untiled =
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options = TestSupport.options { copy(tiling = false) },
                    diagrams = TestSupport.source(svg = TestSupport.WIDE_SVG),
                )
            val w = untiled.warnings.single()
            w.code shouldBe ScaleWarningCode.ILLEGIBLE
            (w.scale < 0.5) shouldBe true
            w.suggestion shouldBe "Kacheln aktivieren"
            w.message shouldBe "unleserlich (Skala ${String.format(java.util.Locale.GERMAN, "%.2f", w.scale)} < 0,50)"
            untiled.layoutSummary.illegibleDiagrams shouldBe 1
            untiled.close()

            val tiled =
                WorkspacePdfExporter.prepare(
                    workspace = fresh(),
                    options = TestSupport.options(),
                    diagrams = TestSupport.source(svg = TestSupport.WIDE_SVG),
                )
            tiled.warnings.size shouldBe 0
            (tiled.layoutSummary.tiledDiagrams >= 1) shouldBe true
            tiled.close()
        }

        test("small diagram is not scaled up and AUTO keeps portrait text pages") {
            val ws = fresh()
            val f = File(TestSupport.tempDir(), "p.pdf")
            val res = TestSupport.export(ws = ws, options = TestSupport.options(), target = f)
            res.warnings.size shouldBe 0
            TestSupport.open(f).use { pdf ->
                (0 until pdf.numberOfPages).all { pdf.getPage(it).mediaBox.width < pdf.getPage(it).mediaBox.height } shouldBe true
            }
        }

        test("wide diagram on AUTO gets a landscape page, text stays portrait") {
            val ws = fresh()
            val f = File(TestSupport.tempDir(), "l.pdf")
            TestSupport.export(
                ws = ws,
                options = TestSupport.options(),
                source = TestSupport.source(svg = TestSupport.MEDIUM_SVG),
                target = f,
            )
            TestSupport.open(f).use { pdf ->
                val landscape = (0 until pdf.numberOfPages).count { pdf.getPage(it).mediaBox.width > pdf.getPage(it).mediaBox.height }
                landscape shouldBe 1
            }
        }

        test("per-document export: one file per document, cover only on request, collision-free names") {
            val ws = fresh()
            val dir = File(TestSupport.tempDir(), "out")
            val res =
                TestSupport.export(
                    ws = ws,
                    options =
                        TestSupport.options(kind = PdfExportKind.PER_DOCUMENT) {
                            copy(cover = false)
                        },
                    target = dir,
                )
            res.files.size shouldBe 5
            res.files
                .map { it.name }
                .toSet()
                .size shouldBe 5
            res.files.map { it.name } shouldContain "chapters-eins.pdf"
            TestSupport.open(res.files.first { it.name == "chapters-eins.pdf" }).use { pdf ->
                TestSupport.text(pdf = pdf, page = 1) shouldContain "Erstes Kapitel"
                TestSupport.text(pdf = pdf, page = 1) shouldContain "Seite 1 von"
            }
        }

        test("diagram folio: one diagram per page, split creates one file per diagram") {
            val ws = fresh()
            val f = File(TestSupport.tempDir(), "folio.pdf")
            val res = TestSupport.export(ws = ws, options = TestSupport.options(kind = PdfExportKind.DIAGRAMS), target = f)
            res.pageCount shouldBe 1
            TestSupport.open(f).use { pdf ->
                TestSupport.text(pdf = pdf, page = 1) shouldContain "Abbildung: organe"
                TestSupport.text(pdf = pdf, page = 1) shouldContain "models/organe.md:"
            }
            val dir = File(TestSupport.tempDir(), "split")
            val split =
                TestSupport.export(
                    ws = ws,
                    options = TestSupport.options(kind = PdfExportKind.DIAGRAMS) { copy(split = true) },
                    target = dir,
                )
            split.files.size shouldBe 1
        }

        test("output guard: existing file needs overwrite, wrong extension, source document, root restriction") {
            val ws = fresh()
            val out = File(TestSupport.tempDir(), "o.pdf")
            val opts = TestSupport.options()

            fun prep() = WorkspacePdfExporter.prepare(workspace = ws, options = opts, diagrams = TestSupport.source())
            WorkspacePdfExporter.write(prepared = prep(), target = out, overwrite = false)
            shouldThrow<PdfExportException> { WorkspacePdfExporter.write(prepared = prep(), target = out, overwrite = false) }.code shouldBe
                PdfExportException.Code.OUTPUT_EXISTS
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.write(prepared = prep(), target = File(out.parentFile, "o.txt"), overwrite = true)
            }.code shouldBe
                PdfExportException.Code.OUTPUT_INVALID
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.write(
                    prepared = prep(),
                    target =
                        File(ws.root, "index.md.pdf").let {
                            File(ws.root, "chapters/eins.md")
                        },
                    overwrite = true,
                )
            }.code shouldBe
                PdfExportException.Code.OUTPUT_INVALID
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.write(
                    prepared = prep(),
                    target = File(ws.root, "../escape.pdf"),
                    overwrite = true,
                    restrictToRoot = ws.root,
                )
            }.code shouldBe
                PdfExportException.Code.PATH_OUTSIDE_ROOT
            WorkspacePdfExporter
                .write(
                    prepared = prep(),
                    target = File(ws.root, ".kuml-out/e.pdf"),
                    overwrite = true,
                    restrictToRoot = ws.root,
                ).files.size shouldBe
                1
        }

        test("output guard: symlink target is refused and no partial file is left on failure") {
            val ws = fresh()
            val d = TestSupport.tempDir()
            val real = File(d, "real.pdf").also { it.writeText("x") }
            runCatching { Files.createSymbolicLink(File(d, "link.pdf").toPath(), real.toPath()) }.onSuccess {
                shouldThrow<PdfExportException> {
                    WorkspacePdfExporter.write(
                        prepared =
                            WorkspacePdfExporter.prepare(
                                workspace = ws,
                                options = TestSupport.options(),
                                diagrams = TestSupport.source(),
                            ),
                        target = File(d, "link.pdf"),
                        overwrite = true,
                    )
                }.code shouldBe PdfExportException.Code.OUTPUT_INVALID
                real.readText() shouldBe "x"
            }
            val small = TestSupport.options { copy(limits = ExportLimits(maxOutputBytes = 1_000)) }
            val target = File(d, "tiny.pdf")
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.write(
                    prepared = WorkspacePdfExporter.prepare(workspace = ws, options = small, diagrams = TestSupport.source()),
                    target = target,
                    overwrite = true,
                )
            }.code shouldBe PdfExportException.Code.LIMIT_OUTPUT_SIZE
            target.exists() shouldBe false
            d.listFiles { f -> f.name.endsWith(".tmp") }!!.size shouldBe 0
        }

        test("limits: pages, documents, diagrams, timeouts") {
            val ws = fresh()
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options =
                        TestSupport.options {
                            copy(limits = ExportLimits(maxPages = 3))
                        },
                    diagrams = TestSupport.source(),
                )
            }.code shouldBe
                PdfExportException.Code.LIMIT_PAGES
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options =
                        TestSupport.options {
                            copy(limits = ExportLimits(maxDocuments = 2))
                        },
                    diagrams = TestSupport.source(),
                )
            }.code shouldBe
                PdfExportException.Code.LIMIT_DOCUMENTS
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options =
                        TestSupport.options {
                            copy(limits = ExportLimits(maxDiagrams = 0))
                        },
                    diagrams = TestSupport.source(),
                )
            }.code shouldBe
                PdfExportException.Code.LIMIT_DIAGRAMS
            val slow =
                DiagramSource {
                    _,
                    _,
                    _,
                    ->
                    Thread.sleep(3_000).let { DiagramArtifact.Rendered(svg = TestSupport.SMALL_SVG, facts = null) }
                }
            val p =
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options =
                        TestSupport.options {
                            copy(limits = ExportLimits(perDiagramTimeout = Duration.ofMillis(200)))
                        },
                    diagrams = slow,
                )
            p.failures.size shouldBe 1
            p.failures.single().message shouldContain "timeout"
            p.close()
            val huge =
                TestSupport.source(
                    svg =
                        "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><rect width='10' height='10'/></svg>" +
                            " ".repeat(2_000),
                )
            WorkspacePdfExporter
                .prepare(
                    workspace = ws,
                    options =
                        TestSupport.options {
                            copy(limits = ExportLimits(maxSvgBytes = 1_000))
                        },
                    diagrams = huge,
                ).also {
                    it.failures.size shouldBe
                        1
                }.close()
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options = TestSupport.options(),
                    diagrams = TestSupport.source(),
                    cancelled = { true },
                )
            }.code shouldBe PdfExportException.Code.CANCELLED
        }

        test("failed and hostile diagrams: placeholder box, no abort, XXE-style SVG rejected") {
            val ws = fresh()
            val evil =
                DiagramSource {
                    _,
                    _,
                    _,
                    ->
                    DiagramArtifact.Rendered(
                        svg =
                            "<?xml version='1.0'?><!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" +
                                "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><text>&x;</text></svg>",
                        facts = null,
                    )
                }
            val f = File(TestSupport.tempDir(), "e.pdf")
            val res = TestSupport.export(ws = ws, options = TestSupport.options(), source = evil, target = f)
            res.failures.size shouldBe 1
            TestSupport.open(f).use { pdf ->
                TestSupport.allText(pdf).replace("\n", " ") shouldContain
                    "Diagramm konnte nicht gerendert werden"
            }
            val failing = DiagramSource { _, _, _ -> DiagramArtifact.Failed("boom") }
            TestSupport
                .export(ws = ws, options = TestSupport.options(), source = failing, target = f)
                .failures
                .single()
                .code shouldBe
                "OKF-E-007"
        }

        test("AI notes: off by default, labelled draft box when on") {
            val ws = fresh()
            val provider = AiNotesProvider { _, _ -> "Eine Erklärung." }
            val f = File(TestSupport.tempDir(), "a.pdf")
            WorkspacePdfExporter.write(
                prepared =
                    WorkspacePdfExporter.prepare(
                        workspace = ws,
                        options = TestSupport.options(),
                        diagrams = TestSupport.source(),
                        aiNotes = provider,
                    ),
                target = f,
                overwrite = true,
            )
            TestSupport.open(f).use { TestSupport.allText(it).contains("KI-Entwurf") shouldBe false }
            WorkspacePdfExporter.write(
                prepared =
                    WorkspacePdfExporter.prepare(
                        workspace = ws,
                        options =
                            TestSupport.options {
                                copy(aiNotes = true)
                            },
                        diagrams = TestSupport.source(),
                        aiNotes = provider,
                    ),
                target = f,
                overwrite = true,
            )
            TestSupport.open(f).use {
                TestSupport.allText(it) shouldContain "KI-Entwurf"
                TestSupport.allText(it) shouldContain "Eine Erklärung."
            }
        }

        test("control characters in frontmatter never break the metadata or the document") {
            val ws =
                TestSupport.workspace(
                    dir = TestSupport.tempDir(),
                    extra =
                        mapOf(
                            "index.md" to
                                "---\ntype: KumlWorkspace\ntitle: \"Ti\u0001tle\"\nauthor: \"A\u0007\"\n---\n# X\n\n- [c](chapters/eins.md)\n",
                        ),
                )
            val f = File(TestSupport.tempDir(), "m.pdf")
            TestSupport.export(ws = ws, options = TestSupport.options { copy(metadata = ExportMetadata()) }, target = f)
            TestSupport.open(f).use { it.documentInformation.title shouldBe "Title" }
        }

        test("the Elements heading is never separated from its table by a page break") {
            val ws = fresh()
            val rows = (1..60).map { n -> ElementRow(kind = "Class", name = "Name$n", detail = null) }
            val facts = DiagramFacts(elements = rows, traces = emptyList(), notes = emptyList())
            val f = File(TestSupport.tempDir(), "t.pdf")
            val src = TestSupport.source(svg = TestSupport.MEDIUM_SVG, facts = facts)
            TestSupport.export(ws = ws, options = TestSupport.options(), source = src, target = f)
            TestSupport.open(f).use { pdf ->
                for (i in 1..pdf.numberOfPages) {
                    val lines = TestSupport.text(pdf = pdf, page = i).trimEnd().lines()
                    val tail = lines.takeLast(4).joinToString(" ")
                    (tail.contains("Elemente") && !tail.contains("Name")) shouldBe false
                }
            }
        }

        test("glyph without a font entry becomes a question mark instead of an exception") {
            val ws = TestSupport.workspace(dir = TestSupport.tempDir(), extra = mapOf("chapters/emoji.md" to "# Emoji\n\nHallo 😀 Welt\n"))
            val f = File(TestSupport.tempDir(), "g.pdf")
            TestSupport.export(ws = ws, options = TestSupport.options(), target = f)
            TestSupport.open(f).use { TestSupport.allText(it) shouldContain "Hallo ? Welt" }
        }

        test("logging hygiene: no document, SVG or frontmatter content in log output") {
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val logger = LoggerFactory.getLogger(WorkspacePdfExporter::class.java) as Logger
            logger.addAppender(appender)
            try {
                val ws = fresh()
                val failing = DiagramSource { _, _, _ -> throw IllegalStateException("secret-script-content") }
                TestSupport.export(
                    ws = ws,
                    options = TestSupport.options(),
                    source = failing,
                    target = File(TestSupport.tempDir(), "l.pdf"),
                )
            } finally {
                logger.detachAppender(appender)
            }
            val text = appender.list.joinToString("\n") { it.formattedMessage }
            text.contains("secret-script-content") shouldBe false
            text.contains("Erika Mustermann") shouldBe false
            text.contains("Muster Handbuch") shouldBe false
            appender.list.size shouldBeGreaterThan 0
        }

        test("markdown: remote images become alt text, local images are embedded only inside the root") {
            val root = TestSupport.tempDir()
            val png = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB)
            javax.imageio.ImageIO.write(png, "png", File(root, "pic.png"))
            val ws =
                TestSupport.workspace(
                    dir = root,
                    extra =
                        mapOf(
                            "chapters/img.md" to
                                "# Bilder\n\n![lokal](../pic.png)\n\n![remote](https://evil.example/x.png)\n\n![out](../../outside.png)\n",
                        ),
                )
            val f = File(TestSupport.tempDir(), "i.pdf")
            TestSupport.export(ws = ws, options = TestSupport.options(), target = f)
            TestSupport.open(f).use { pdf ->
                val images = pdf.pages.sumOf { p -> p.resources.xObjectNames.count { p.resources.isImageXObject(it) } }
                images shouldBe 1
            }
        }
    })
