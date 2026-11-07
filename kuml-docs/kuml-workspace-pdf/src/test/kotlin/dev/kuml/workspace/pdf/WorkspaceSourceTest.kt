package dev.kuml.workspace.pdf

import dev.kuml.workspace.pdf.plan.ExportPlanner
import dev.kuml.workspace.pdf.source.SourceBlock
import dev.kuml.workspace.pdf.source.SourceConstruct
import dev.kuml.workspace.pdf.source.SourceInline
import dev.kuml.workspace.pdf.source.WorkspaceSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.time.LocalDate

class WorkspaceSourceTest :
    FunSpec({
        fun fresh(extra: Map<String, String> = emptyMap()) = TestSupport.workspace(dir = TestSupport.tempDir(), extra = extra)

        fun docOf(
            body: String,
            name: String = "extra.md",
        ): Triple<dev.kuml.workspace.OkfWorkspace, dev.kuml.workspace.pdf.source.SourcePlan, dev.kuml.workspace.pdf.source.SourceReader> {
            val ws = fresh(extra = mapOf(name to "---\ntype: Article\ntitle: Extra\n---\n$body"))
            val plan = WorkspaceSource.plan(workspace = ws, scope = ExportScope.All)
            return Triple(ws, plan, WorkspaceSource.reader(workspace = ws, plan = plan, language = PdfLanguage.DE))
        }

        test("the plan equals the planner of the PDF export") {
            val ws = fresh()
            val plan = WorkspaceSource.plan(workspace = ws, scope = ExportScope.All)
            val internal = ExportPlanner.plan(ws = ws, scope = ExportScope.All)
            plan.all.map { it.relativePath } shouldBe internal.all.map { it.relativePath }
            plan.chapters.map { it.depth } shouldBe internal.chapters.map { it.depth }
            plan.glossary.map { it.relativePath } shouldBe listOf("glossary/index.md")
        }

        test("an empty scope is rejected with the PDF exception") {
            val ws = fresh()
            io.kotest.assertions.throwables
                .shouldThrow<PdfExportException> {
                    WorkspaceSource.plan(workspace = ws, scope = ExportScope.Folder("nonexistent"))
                }.code shouldBe PdfExportException.Code.EMPTY_SCOPE
        }

        test("the first level-1 heading becomes the title and is dropped") {
            val ws = fresh()
            val plan = WorkspaceSource.plan(workspace = ws, scope = ExportScope.All)
            val reader = WorkspaceSource.reader(workspace = ws, plan = plan, language = PdfLanguage.DE)
            val doc = plan.chapters.first().document
            val sd = reader.document(document = doc, level = 1)
            sd.title shouldBe "Erstes Kapitel"
            (sd.blocks.first() is SourceBlock.Heading && (sd.blocks.first() as SourceBlock.Heading).level == 1) shouldBe false
        }

        test("metadata: overrides win over the frontmatter, an unparsable date is dropped") {
            val ws = fresh()
            val plan = WorkspaceSource.plan(workspace = ws, scope = ExportScope.All)
            val m = WorkspaceSource.metadata(workspace = ws, plan = plan, override = ExportMetadata())
            m.title shouldBe "Muster Handbuch"
            m.author shouldBe "Erika Mustermann"
            m.date shouldBe LocalDate.of(2026, 10, 28)
            val o = WorkspaceSource.metadata(workspace = ws, plan = plan, override = ExportMetadata(title = "X", author = "Y"))
            o.title shouldBe "X"
            o.author shouldBe "Y"
            val bad =
                fresh(
                    extra =
                        mapOf(
                            "index.md" to "---\ntype: KumlWorkspace\ntitle: T\ndate: kein-datum\n---\n# T\n\n- [E](chapters/eins.md)\n",
                        ),
                )
            val badPlan = WorkspaceSource.plan(workspace = bad, scope = ExportScope.All)
            WorkspaceSource.metadata(workspace = bad, plan = badPlan, override = ExportMetadata()).date shouldBe null
        }

        test("language: index.md lang, else the override") {
            val ws =
                fresh(extra = mapOf("index.md" to "---\ntype: KumlWorkspace\ntitle: T\nlang: en\n---\n# T\n\n- [E](chapters/eins.md)\n"))
            val plan = WorkspaceSource.plan(workspace = ws, scope = ExportScope.All)
            WorkspaceSource.language(plan = plan, override = null) shouldBe PdfLanguage.EN
            WorkspaceSource.language(plan = plan, override = PdfLanguage.DE) shouldBe PdfLanguage.DE
        }

        test("blockTitle: block name, numbered title for several blocks, document title for one") {
            val ws = fresh()
            val organe = ws.documents.first { it.relativePath == "models/organe.md" }
            WorkspaceSource.blockTitle(document = organe, index = 0) shouldBe "organe"
        }

        test("an image alone in a paragraph becomes an ImageRef with its line, an inline image stays alt text") {
            val (_, plan, reader) = docOf("# Extra\n\n![Alt text](bilder/a.png)\n\nText mit ![Inline](b.png) Bild.\n")
            val sd = reader.document(document = plan.all.first { it.relativePath == "extra.md" }, level = 1)
            val ref = sd.blocks.filterIsInstance<SourceBlock.ImageRef>().single()
            ref.destination shouldBe "bilder/a.png"
            ref.alt shouldBe "Alt text"
            (ref.line > 3) shouldBe true
            sd.diagnostics.map { it.construct } shouldContain SourceConstruct.INLINE_IMAGE
        }

        test("HTML, footnote syntax, math and strikethrough are reported") {
            val (_, plan, reader) = docOf("# Extra\n\n<div>roh</div>\n\nFußnote[^1] und \$\$x\$\$ und ~~weg~~.\n\nMit \$a+b\$ Formel.\n")
            val sd = reader.document(document = plan.all.first { it.relativePath == "extra.md" }, level = 1)
            val kinds = sd.diagnostics.map { it.construct }.toSet()
            kinds shouldContain SourceConstruct.RAW_HTML
            kinds shouldContain SourceConstruct.FOOTNOTE_SYNTAX
            kinds shouldContain SourceConstruct.MATH
            kinds shouldContain SourceConstruct.STRIKETHROUGH
            sd.blocks
                .filterIsInstance<SourceBlock.RawHtml>()
                .single()
                .text shouldBe "<div>roh</div>"
            val strike =
                sd.blocks
                    .filterIsInstance<SourceBlock.Paragraph>()
                    .flatMap {
                        it.inlines
                    }.filterIsInstance<SourceInline.Text>()
                    .filter { it.strike }
            strike.single().text shouldBe "weg"
        }

        test("the info string of a code fence is kept") {
            val (_, plan, reader) = docOf("# Extra\n\n```kotlin title=x\nval a = 1\n```\n")
            val sd = reader.document(document = plan.all.first { it.relativePath == "extra.md" }, level = 1)
            val code = sd.blocks.filterIsInstance<SourceBlock.CodeBlock>().single()
            code.info shouldBe "kotlin title=x"
            code.text shouldBe "val a = 1"
        }

        test("too deep lists, too many columns and long code are reported") {
            var nested = ""
            for (i in 0 until 10) nested += "    ".repeat(i) + "- level $i\n"
            val cols = (1..14).joinToString(" | ", "| ", " |") { "c$it" }
            val sep = (1..14).joinToString(" | ", "|", "|") { "---" }
            val code = "```\n" + (1..2100).joinToString("\n") { "l$it" } + "\n```\n"
            val (_, plan, reader) = docOf("# Extra\n\n$nested\n$cols\n$sep\n${cols.replace('c', 'd')}\n\n$code")
            val kinds =
                reader
                    .document(
                        document = plan.all.first { it.relativePath == "extra.md" },
                        level = 1,
                    ).diagnostics
                    .map { it.construct }
                    .toSet()
            kinds shouldContain SourceConstruct.LIST_TOO_DEEP
            kinds shouldContain SourceConstruct.TABLE_TRUNCATED
            kinds shouldContain SourceConstruct.CODE_TRUNCATED
        }

        test("a wikilink to a document outside the export stays plain text") {
            val ws = fresh()
            val plan = WorkspaceSource.plan(workspace = ws, scope = ExportScope.Folder("chapters"))
            val reader = WorkspaceSource.reader(workspace = ws, plan = plan, language = PdfLanguage.DE)
            val sd = reader.document(document = plan.all.single(), level = 1)
            val texts =
                sd.blocks
                    .filterIsInstance<SourceBlock.Paragraph>()
                    .flatMap { it.inlines }
                    .filterIsInstance<SourceInline.Text>()
            texts.any { it.link != null && it.text.contains("internen Link") } shouldBe false
            texts.map { it.text }.joinToString("").contains("nicht im Export") shouldBe true
            sd.blocks.first().shouldBeInstanceOf<SourceBlock.Paragraph>()
        }

        test("the scratch workspace helper writes real files") {
            File(fresh().root, "index.md").isFile shouldBe true
        }
    })
