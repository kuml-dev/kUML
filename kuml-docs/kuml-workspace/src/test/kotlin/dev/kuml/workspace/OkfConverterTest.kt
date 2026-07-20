package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

/**
 * Pure-unit tests for [OkfConverter] — no script evaluation involved (FT-7).
 * The `--to okf` type-mapping side ([dev.kuml.cli.workspace.OkfTypeMapper]) lives in
 * `kuml-cli` and is exercised end-to-end by `WorkspaceConvertCommandTest` instead.
 */
class OkfConverterTest :
    FunSpec({

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-converter").toFile()

        test("wrapAsOkf followed by extractBlocks recovers the original DSL source (kts->okf->kts)") {
            val dsl =
                """
                classDiagram(name = "Hello") {
                    val a = classOf(name = "A")
                }
                """.trimIndent()

            val note = OkfConverter.wrapAsOkf(dslSource = dsl, typeId = "UmlClassDiagram", title = "Hello")
            // Built via explicit line-joining rather than a trimIndent()'d template literal:
            // interpolating a multi-line `dsl` value into a trimIndent() block would let the
            // dsl's own (differently-indented) continuation lines skew the common-indent
            // calculation across the whole literal.
            val expected =
                listOf(
                    "---",
                    "type: UmlClassDiagram",
                    "title: Hello",
                    "---",
                    "",
                    "# Hello",
                    "",
                    "```kuml",
                    dsl,
                    "```",
                    "",
                ).joinToString("\n")
            note shouldBe expected

            val root = tempWorkspace()
            val docFile = File(root, "hello.md")
            docFile.writeText(note)
            val ws = WorkspaceScanner.scan(root)
            val doc = ws.documents.single()

            val extracted = OkfConverter.extractBlocks(doc)
            extracted.size shouldBe 1
            extracted.single().stem shouldBe "hello"
            extracted.single().dslSource shouldBe dsl.trimEnd('\n')

            root.deleteRecursively()
        }

        test("wrapAsOkf trims trailing newlines from dslSource to exactly one before the closing fence") {
            val note =
                OkfConverter.wrapAsOkf(dslSource = "classDiagram(name = \"X\") { }\n\n\n", typeId = "UmlClassDiagram", title = "X")
            note shouldContainOnce "classDiagram(name = \"X\") { }\n```\n"
        }

        test("extractBlocks names a single block after the document's own file stem") {
            val root = tempWorkspace()
            File(root, "domain-classes.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml
                |classDiagram(name = "X") { }
                |```
                """.trimMargin(),
            )
            val ws = WorkspaceScanner.scan(root)
            val extracted = OkfConverter.extractBlocks(ws.documents.single())
            extracted.map { it.stem } shouldBe listOf("domain-classes")

            root.deleteRecursively()
        }

        test("extractBlocks numbers multiple blocks <stem>-1, <stem>-2, ... (1-based)") {
            val root = tempWorkspace()
            File(root, "multi.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml
                |classDiagram(name = "First") { }
                |```
                |
                |```kuml
                |classDiagram(name = "Second") { }
                |```
                """.trimMargin(),
            )
            val ws = WorkspaceScanner.scan(root)
            val extracted = OkfConverter.extractBlocks(ws.documents.single())
            extracted.map { it.stem } shouldBe listOf("multi-1", "multi-2")
            extracted.map { it.dslSource } shouldBe
                listOf("""classDiagram(name = "First") { }""", """classDiagram(name = "Second") { }""")

            root.deleteRecursively()
        }

        test("a ```kuml {name=\"...\"} attribute wins over the document stem, even for a single block") {
            val root = tempWorkspace()
            File(root, "doc.md").writeText(
                """
                |---
                |type: UmlClassDiagram
                |---
                |```kuml {name="custom-name"}
                |classDiagram(name = "X") { }
                |```
                """.trimMargin(),
            )
            val ws = WorkspaceScanner.scan(root)
            val extracted = OkfConverter.extractBlocks(ws.documents.single())
            extracted.single().stem shouldBe "custom-name"

            root.deleteRecursively()
        }

        test("extractBlocks returns an empty list for a document with no ```kuml block") {
            val root = tempWorkspace()
            File(root, "prose.md").writeText(
                """
                |---
                |type: Concept
                |---
                |Just prose, no diagram.
                """.trimMargin(),
            )
            val ws = WorkspaceScanner.scan(root)
            OkfConverter.extractBlocks(ws.documents.single()).shouldBeEmpty()

            root.deleteRecursively()
        }

        test("sanitizeStem strips path components and unsafe characters") {
            OkfConverter.sanitizeStem("../../etc/passwd") shouldBe "passwd"
            OkfConverter.sanitizeStem("a/b\\c") shouldBe "c"
            // Trailing '_'/'-'/'.' characters are trimmed off after the character replacement.
            OkfConverter.sanitizeStem("weird name!@#") shouldBe "weird_name"
        }

        test("sanitizeStem falls back to 'block' when nothing safe remains") {
            OkfConverter.sanitizeStem("..") shouldBe "block"
            OkfConverter.sanitizeStem("///") shouldBe "block"
            OkfConverter.sanitizeStem("") shouldBe "block"
        }

        test("wrapAsOkf quotes a title containing a colon so FrontmatterParser still resolves 'title:' correctly") {
            val note = OkfConverter.wrapAsOkf(dslSource = "classDiagram(name = \"X\") { }", typeId = "UmlClassDiagram", title = "Foo: Bar")
            val fm = FrontmatterParser.parse(note)
            fm.title shouldBe "Foo: Bar"
            fm.type shouldBe "UmlClassDiagram"
        }
    })

private infix fun String.shouldContainOnce(substring: String) {
    val count = Regex(Regex.escape(substring)).findAll(this).count()
    count shouldBe 1
}
