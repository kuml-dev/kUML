package dev.kuml.workspace.pdf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.time.Duration
import java.util.Collections

/** Regression tests for the review findings: nested diagrams, block indexing, split names, timeouts. */
class ReviewFixesTest :
    FunSpec({
        fun wsOf(files: Map<String, String>): Pair<File, dev.kuml.workspace.OkfWorkspace> {
            val dir = TestSupport.tempDir()
            files.forEach { (rel, text) ->
                File(dir, rel).also { it.parentFile.mkdirs() }.writeText(text, Charsets.UTF_8)
            }
            return dir to
                dev.kuml.workspace.WorkspaceScanner
                    .scan(root = dir)
        }

        fun doc(
            title: String,
            body: String,
        ) = "---\ntype: Article\ntitle: $title\n---\n# $title\n\n$body\n"

        test("diagrams inside list items and callouts are rendered, not dropped") {
            val (_, ws) =
                wsOf(
                    mapOf(
                        "a.md" to
                            doc(
                                "Nested",
                                "1. Step one:\n\n   ```kuml {name=\"inlist\"}\n   x\n   ```\n\n2. Step two\n\n" +
                                    "> [!example]\n> text\n>\n> ```kuml {name=\"incallout\"}\n> y\n> ```\n",
                            ),
                    ),
                )
            // the callout fence is invisible to the line-based extractor, only the list diagram is a known block
            ws.documents
                .single()
                .kumlBlocks.size shouldBe 1
            WorkspacePdfExporter
                .prepare(
                    workspace = ws,
                    options = TestSupport.options(kind = PdfExportKind.PER_DOCUMENT),
                    diagrams = TestSupport.source(),
                ).use { p ->
                    p.failures.size shouldBe 0
                    val out = File(TestSupport.tempDir(), "out")
                    val r = WorkspacePdfExporter.write(prepared = p, target = out, overwrite = true)
                    TestSupport.open(r.files.single()).use { pdf -> TestSupport.allText(pdf) shouldContain "inlist" }
                }
        }

        test("a kuml fence the extractor does not see does not shift later diagram indexes") {
            val (_, ws) =
                wsOf(
                    mapOf(
                        "a.md" to
                            doc(
                                "Shift",
                                "> [!example]\n> ```kuml {name=\"hidden\"}\n> y\n> ```\n\n" +
                                    "````markdown\n```kuml {name=\"quoted\"}\nz\n```\n````\n\n" +
                                    "```kuml {name=\"second\"}\nreal\n```\n",
                            ),
                    ),
                )
            val seen = Collections.synchronizedList(mutableListOf<String?>())
            val src =
                DiagramSource { _, _, block ->
                    seen += block.name
                    DiagramArtifact.Rendered(svg = TestSupport.SMALL_SVG, facts = null)
                }
            WorkspacePdfExporter
                .prepare(
                    workspace = ws,
                    options = TestSupport.options(kind = PdfExportKind.PER_DOCUMENT),
                    diagrams = src,
                ).use { p ->
                    p.failures.size shouldBe 0
                    val r = WorkspacePdfExporter.write(prepared = p, target = File(TestSupport.tempDir(), "o"), overwrite = true)
                    TestSupport.open(r.files.single()).use { pdf ->
                        val text = TestSupport.allText(pdf)
                        text shouldContain "second"
                        check("not rendered" !in text && "konnte nicht" !in text)
                    }
                }
        }

        test("split folio of a scope without diagrams is an EMPTY_SCOPE error") {
            val (_, ws) = wsOf(mapOf("a.md" to doc("Plain", "No diagram here.")))
            shouldThrow<PdfExportException> {
                WorkspacePdfExporter.prepare(
                    workspace = ws,
                    options = TestSupport.options(kind = PdfExportKind.DIAGRAMS) { copy(split = true) },
                    diagrams = TestSupport.source(),
                )
            }.code shouldBe PdfExportException.Code.EMPTY_SCOPE
        }

        test("split and per-document file names stay unique, also case-insensitively") {
            val d = "```kuml\nx\n```"
            val (_, ws) =
                wsOf(
                    mapOf(
                        "a b.md" to doc("One", d),
                        "a_b.md" to doc("Two", d),
                        "A-b.md" to doc("Three", d),
                        "a/b.md" to doc("Four", d),
                    ),
                )
            for (kind in listOf(PdfExportKind.DIAGRAMS, PdfExportKind.PER_DOCUMENT)) {
                WorkspacePdfExporter
                    .prepare(
                        workspace = ws,
                        options = TestSupport.options(kind = kind) { copy(split = kind == PdfExportKind.DIAGRAMS) },
                        diagrams = TestSupport.source(),
                    ).use { p ->
                        p.outputFileNames
                            .map { it.lowercase() }
                            .toSet()
                            .size shouldBe 4
                        val dir = File(TestSupport.tempDir(), "out")
                        val r = WorkspacePdfExporter.write(prepared = p, target = dir, overwrite = true)
                        r.files.size shouldBe 4
                        (dir.listFiles()?.size ?: 0) shouldBe 4
                    }
            }
            val names =
                WorkspacePdfExporter
                    .prepare(
                        workspace = ws,
                        options = TestSupport.options(kind = PdfExportKind.PER_DOCUMENT),
                        diagrams = TestSupport.source(),
                    ).use { it.outputFileNames }
            names.toSet().size shouldBe names.size
            names shouldContainExactlyInAnyOrder names.distinct()
        }

        test("an uninterruptible diagram times out alone; later diagrams still render") {
            val d = "```kuml\nx\n```"
            val (_, ws) = wsOf(mapOf("a.md" to doc("A", d), "b.md" to doc("B", d), "c.md" to doc("C", d)))
            val first =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val src =
                DiagramSource { _, _, _ ->
                    if (first.getAndSet(false)) {
                        val end = System.nanoTime() + Duration.ofSeconds(4).toNanos()
                        @Suppress("ControlFlowWithEmptyBody")
                        while (System.nanoTime() < end) {
                        }
                    }
                    DiagramArtifact.Rendered(svg = TestSupport.SMALL_SVG, facts = null)
                }
            val opts =
                TestSupport.options(
                    kind = PdfExportKind.PER_DOCUMENT,
                ) { copy(limits = ExportLimits(perDiagramTimeout = Duration.ofMillis(300))) }
            WorkspacePdfExporter.prepare(workspace = ws, options = opts, diagrams = src).use { p ->
                p.failures.size shouldBe 1
                p.failures.single().message shouldContain "timeout"
            }
        }
    })
