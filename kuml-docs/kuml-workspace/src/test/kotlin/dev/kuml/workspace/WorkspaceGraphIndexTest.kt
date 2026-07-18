package dev.kuml.workspace

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class WorkspaceGraphIndexTest :
    FunSpec({

        fun tempWorkspace(): File = Files.createTempDirectory("kuml-okf-graph").toFile()

        test("a relative link between two documents is indexed both forward and as a backlink") {
            val root = tempWorkspace()
            File(root, "index.md").writeText(
                """
                |---
                |type: KumlWorkspace
                |---
                |See [Vorstand](./concepts/Vorstand.md) for details.
                """.trimMargin(),
            )
            File(root, "concepts").mkdirs()
            File(root, "concepts/Vorstand.md").writeText(
                """
                |---
                |type: Concept
                |---
                |Body.
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val index = WorkspaceGraphIndex.build(ws)

            val indexDoc = ws.documents.single { it.relativePath == "index.md" }
            val vorstandDoc = ws.documents.single { it.relativePath == "concepts/Vorstand.md" }

            index.outgoing(indexDoc).map { it.to } shouldBe listOf(vorstandDoc)
            index.backlinks(vorstandDoc).map { it.from } shouldBe listOf(indexDoc)
            index.backlinks(indexDoc).shouldBeEmpty()
            index.outgoing(vorstandDoc).shouldBeEmpty()

            root.deleteRecursively()
        }

        test("two documents linking to the same target both appear in its backlinks, in scan order") {
            val root = tempWorkspace()
            File(root, "a.md").writeText("---\ntype: Concept\n---\nSee [target](./target.md).\n")
            File(root, "b.md").writeText("---\ntype: Concept\n---\nAlso see [target](./target.md).\n")
            File(root, "target.md").writeText("---\ntype: Concept\n---\nBody.\n")

            val ws = WorkspaceScanner.scan(root)
            val index = WorkspaceGraphIndex.build(ws)

            val targetDoc = ws.documents.single { it.relativePath == "target.md" }
            index.backlinks(targetDoc).map { it.from.relativePath } shouldBe listOf("a.md", "b.md")

            root.deleteRecursively()
        }

        test("external, mailto, and anchor-only links are not indexed") {
            val root = tempWorkspace()
            File(root, "index.md").writeText(
                """
                |---
                |type: KumlWorkspace
                |---
                |[Website](https://kuml.dev), [Mail](mailto:info@kuml.dev), [Here](#section).
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val index = WorkspaceGraphIndex.build(ws)

            val doc = ws.documents.single()
            index.outgoing(doc).shouldBeEmpty()

            root.deleteRecursively()
        }

        test("a link that resolves outside the workspace root is not indexed") {
            val root = tempWorkspace()
            File(root, "index.md").writeText(
                """
                |---
                |type: KumlWorkspace
                |---
                |[Escape](../../../etc/passwd.md).
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val index = WorkspaceGraphIndex.build(ws)

            val doc = ws.documents.single()
            index.outgoing(doc).shouldBeEmpty()

            root.deleteRecursively()
        }

        test("a broken link (target file does not exist) is not indexed but does not throw") {
            val root = tempWorkspace()
            File(root, "index.md").writeText(
                """
                |---
                |type: KumlWorkspace
                |---
                |[Missing](./nonexistent.md).
                """.trimMargin(),
            )

            val ws = WorkspaceScanner.scan(root)
            val index = WorkspaceGraphIndex.build(ws)

            val doc = ws.documents.single()
            index.outgoing(doc).shouldBeEmpty()

            root.deleteRecursively()
        }

        test("an empty workspace produces an index with no entries") {
            val root = tempWorkspace()
            File(root, "index.md").writeText("---\ntype: KumlWorkspace\n---\nNo links here.\n")

            val ws = WorkspaceScanner.scan(root)
            val index = WorkspaceGraphIndex.build(ws)

            val doc = ws.documents.single()
            index.outgoing(doc).shouldBeEmpty()
            index.backlinks(doc).shouldBeEmpty()

            root.deleteRecursively()
        }
    })
