package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

/**
 * Parity between [WorkspaceScanner.scan] (file-based) and [OkfDocumentParser.parse]
 * (in-memory buffer): the same document must parse to an identical [OkfDocument]
 * either way — this is the invariant the kUML Desktop document editor relies on when
 * it re-parses an in-memory buffer instead of re-scanning the whole workspace.
 */
class OkfDocumentParserTest :
    FunSpec({

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-document-parser").toFile()

        test("parse(text) and scan() produce an identical OkfDocument for the same content") {
            val root = tempWorkspace()
            try {
                val text =
                    """
                    |---
                    |type: UmlClassDiagram
                    |title: Domain
                    |tags: [a, b]
                    |---
                    |# Domain
                    |
                    |[back](../index.md)
                    |
                    |```kuml {name="domain"}
                    |classDiagram(name = "X") { }
                    |```
                    """.trimMargin()
                val sub = File(root, "articles").apply { mkdirs() }
                val file = File(sub, "domain.md").apply { writeText(text) }

                val scanned = WorkspaceScanner.scan(root = root).documents.single()
                val parsed = OkfDocumentParser.parse(root = root, file = file, text = text)

                parsed shouldBe scanned
            } finally {
                root.deleteRecursively()
            }
        }

        test("relativePath uses '/'-separators regardless of nesting") {
            val root = tempWorkspace()
            try {
                val sub = File(root, "a/b/c").apply { mkdirs() }
                val file = File(sub, "leaf.md").apply { writeText("---\ntype: Concept\n---\nBody") }
                val doc = OkfDocumentParser.parse(root = root, file = file, text = file.readText())
                doc.relativePath shouldBe "a/b/c/leaf.md"
            } finally {
                root.deleteRecursively()
            }
        }

        test("parseFile refuses a document above the size cap") {
            val root = tempWorkspace()
            try {
                val file = File(root, "huge.md")
                file.writeText("x".repeat(1024))
                // Sanity: a normal file parses fine.
                OkfDocumentParser.parseFile(root = root, file = file)

                // Sparse-allocate a file just over the cap (java.io.RandomAccessFile.setLength
                // does not write the intervening bytes) so the test stays fast.
                java.io.RandomAccessFile(file, "rw").use { it.setLength(OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES + 1) }

                val result = runCatching { OkfDocumentParser.parseFile(root = root, file = file) }
                result.isFailure shouldBe true
                result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
            } finally {
                root.deleteRecursively()
            }
        }

        test("MAX_MD_FILE_SIZE_BYTES matches the documented 20 MiB cap") {
            OkfDocumentParser.MAX_MD_FILE_SIZE_BYTES shouldBe 20L * 1024 * 1024
        }

        test("extractLinks finds every Markdown link with its 1-based line number") {
            val text =
                """
                |# Title
                |
                |[one](./a.md)
                |Some text [two](./b.md) inline.
                """.trimMargin()
            val links = OkfDocumentParser.extractLinks(text)
            links.map { it.target } shouldBe listOf("./a.md", "./b.md")
            links.map { it.line } shouldBe listOf(3, 4)
        }
    })
